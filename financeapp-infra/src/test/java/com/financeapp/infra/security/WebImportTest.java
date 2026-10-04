package com.financeapp.infra.security;

import com.financeapp.core.account.Account;
import com.financeapp.core.available.HorizonType;
import com.financeapp.core.export.Json;
import com.financeapp.core.money.Money;
import com.financeapp.core.service.WebImportService;
import com.financeapp.core.service.WebImportService.Kind;
import com.financeapp.core.service.WebImportService.Target;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.infra.db.SqliteTestDb;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.AEADBadTagException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sauvegarde produite par la version web (WebCrypto) a partir des donnees exportees par
 * l'application (WebParityTest), apres des saisies sur le site
 * (web/src/store/webBackupForDesktop.test.ts) : l'application la dechiffre, reprend les
 * nouvelles operations, et retrouve les memes soldes et le meme disponible reel que le site.
 */
class WebImportTest {

    private static final Path FIXTURE = Path.of("..", "web", "src", "store", "fixtures", "web-backup-for-desktop.json");

    @TempDir
    Path dir;

    @SuppressWarnings("unchecked")
    @Test
    void importsTheOperationsEnteredOnTheWebsite() throws Exception {
        SqliteTestDb db = new SqliteTestDb(dir, WebParityTest.TODAY);
        WebParityTest.scenario(db);
        Map<String, Money> before = balancesByName(db);

        Map<String, Object> fixture = (Map<String, Object>) Json.parse(Files.readString(FIXTURE, StandardCharsets.UTF_8));
        String backup = Json.write(fixture.get("backup"));
        char[] password = ((String) fixture.get("password")).toCharArray();
        Map<String, Object> expected = (Map<String, Object>) fixture.get("expected");

        assertEquals("Téléphone", WebBackupReader.profileName(backup));
        assertThrows(AEADBadTagException.class, () -> WebBackupReader.read(backup, "pas le bon mot de passe".toCharArray()));
        assertThrows(WebBackupReader.NotAWebBackupException.class, () -> WebBackupReader.read("{\"a\":1}", password));
        String data = WebBackupReader.read(backup, password).dataJson();

        WebImportService service = new WebImportService(db.accounts, db.categories, db.transactionRepo, db.recurring,
                db.tags, db.importRepo, db.settings);
        List<WebImportService.WebAccount> webAccounts = service.accounts(data);
        Map<String, WebImportService.WebAccount> byName = webAccounts.stream()
                .collect(Collectors.toMap(WebImportService.WebAccount::name, a -> a));
        assertNotNull(byName.get("Compte courant").suggestedAccountId(), "meme nom : associe d'office");
        assertNull(byName.get("Espèces").suggestedAccountId(), "compte cree sur le site");
        Map<Long, Target> targets = new HashMap<>(WebImportService.defaultTargets(webAccounts));
        assertInstanceOf(Target.Skip.class, targets.get(byName.get("Espèces").webId()), "non importe par defaut");
        targets.put(byName.get("Espèces").webId(), new Target.Create());

        WebImportService.Plan plan = service.plan(data, targets);
        Map<String, List<Kind>> kinds = plan.items().stream().collect(Collectors.groupingBy(
                i -> i.label() + " " + i.date(), Collectors.mapping(WebImportService.Item::kind, Collectors.toList())));
        assertEquals(List.of(Kind.NEW, Kind.NEW), kinds.get("Boulangerie 2026-10-12"), "deux achats identiques");
        assertEquals(List.of(Kind.NEW), kinds.get("Vétérinaire 2026-10-10"));
        assertEquals(List.of(Kind.NEW), kinds.get("Courses hebdo 2026-10-10"));
        assertEquals(List.of(Kind.NEW), kinds.get("Loyer 2026-11-05"), "echeance ignoree sur le site");
        assertEquals(List.of(Kind.REALIZES_PLANNED), kinds.get("Traiteur (prévu) 2026-10-12"));
        assertEquals(List.of(Kind.MODIFIED), kinds.get("Station Total 2026-10-06"));
        assertEquals(List.of(Kind.NEW), kinds.get("Épargne web 2026-10-12"), "un virement = une ligne");
        assertEquals(List.of(Kind.NEW), kinds.get("Fleurs 2026-10-11"));
        assertEquals(List.of(Kind.NEW), kinds.get("Coiffeur 2026-10-20"));
        assertEquals(List.of(Kind.ALREADY_PRESENT), kinds.get("Supermarché 2026-10-02"));
        assertEquals(List.of(Kind.ALREADY_PRESENT), kinds.get("Épargne 2026-10-01"));
        assertEquals(8, plan.count(Kind.NEW));
        assertEquals(0, plan.count(Kind.POSSIBLE_DUPLICATE));
        assertEquals(1, plan.cancelled(), "operation annulee sans echeance : sans effet");
        WebImportService.Item weekly = plan.items().stream().filter(i -> i.label().equals("Courses hebdo")).findFirst()
                .orElseThrow();
        assertEquals(LocalDate.of(2026, 10, 9), weekly.occurrenceDate(), "rattachee a l'echeance du desktop");

        Set<Integer> chosen = plan.items().stream().filter(i -> i.kind().includedByDefault())
                .map(WebImportService.Item::index).collect(Collectors.toSet());
        WebImportService.Result result = service.commit(data, targets, chosen, "telephone.json");
        assertEquals(8, result.created());
        assertEquals(1, result.realized());
        assertEquals(1, result.accountsCreated());
        assertEquals(1, result.categoriesCreated(), "Animaux test");

        // Memes soldes et meme disponible reel que sur le site
        Map<String, Object> webBalances = (Map<String, Object>) expected.get("balances");
        Map<String, Money> after = balancesByName(db);
        for (Map.Entry<String, Object> e : webBalances.entrySet()) {
            assertEquals((long) (Long) e.getValue(), after.get(e.getKey()).toMinorUnits(), e.getKey());
        }
        assertEquals(expected.get("endOfMonth"), db.available.compute(HorizonType.END_OF_MONTH, null).available().toMinorUnits());
        assertEquals(expected.get("custom"), db.available.compute(HorizonType.CUSTOM_DATE, LocalDate.of(2026, 11, 20))
                .available().toMinorUnits());
        assertTrue(db.transactionRepo.findNeedingReview().isEmpty(), "saisies de l'utilisateur : rien a valider");
        assertTrue(db.categories.findAll().stream().anyMatch(c -> c.name().equals("Animaux test")));
        assertEquals(TransactionStatus.COMPLETED, db.transactionRepo.findById(6).orElseThrow().status(), "Traiteur");

        // Reimporter le meme fichier n'ajoute rien
        WebImportService.Plan again = service.plan(data, targetsAfter(service, data, targets, db));
        assertEquals(0, again.count(Kind.NEW), again.items().stream().filter(i -> i.kind() == Kind.NEW)
                .map(WebImportService.Item::label).toList().toString());
        assertEquals(0, again.count(Kind.REALIZES_PLANNED));

        // Defaire les imports : retour a l'etat initial
        result.batchIds().forEach(db.importRepo::undo);
        Map<String, Money> undone = balancesByName(db);
        before.forEach((name, money) -> assertEquals(money, undone.get(name), name));
        assertEquals(TransactionStatus.PLANNED, db.transactionRepo.findById(6).orElseThrow().status());
    }

    /** Apres l'import, le compte cree porte le nom du compte du site : il est associe d'office. */
    private static Map<Long, Target> targetsAfter(WebImportService service, String data, Map<Long, Target> previous,
                                                  SqliteTestDb db) {
        Map<Long, Target> targets = new HashMap<>(WebImportService.defaultTargets(service.accounts(data)));
        targets.forEach((k, v) -> assertInstanceOf(Target.Existing.class, v));
        assertEquals(previous.size(), targets.size());
        return targets;
    }

    private static Map<String, Money> balancesByName(SqliteTestDb db) {
        Map<Long, Money> balances = db.accounts.balances();
        return db.accounts.findAll().stream().collect(Collectors.toMap(Account::name, a -> balances.get(a.id())));
    }
}
