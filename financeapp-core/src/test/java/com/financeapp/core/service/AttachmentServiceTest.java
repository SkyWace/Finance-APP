package com.financeapp.core.service;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.attachment.Attachment;
import com.financeapp.core.attachment.AttachmentType;
import com.financeapp.core.testing.TestApp;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionStatus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 3);
    private final TestApp app = new TestApp(TODAY);
    private final Account checking = app.account("Courant", AccountType.CHECKING, "1000");
    private final Transaction repair = app.expense(checking, TODAY, "Réparation lave-linge", "180", TransactionStatus.COMPLETED);

    static byte[] pdf() {
        return "%PDF-1.7\n1 0 obj << >> endobj\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] png() {
        byte[] b = new byte[64];
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(sig, 0, b, 0, sig.length);
        return b;
    }

    @Test
    void formatsAreRecognisedByTheirContentNotTheirName() {
        assertEquals(AttachmentType.PDF, AttachmentType.detect(pdf()).orElseThrow());
        assertEquals(AttachmentType.PNG, AttachmentType.detect(png()).orElseThrow());
        byte[] jpeg = new byte[32];
        jpeg[0] = (byte) 0xFF; jpeg[1] = (byte) 0xD8; jpeg[2] = (byte) 0xFF;
        assertEquals(AttachmentType.JPEG, AttachmentType.detect(jpeg).orElseThrow());
        byte[] heic = new byte[32];
        System.arraycopy("ftypheic".getBytes(StandardCharsets.US_ASCII), 0, heic, 4, 8);
        assertEquals(AttachmentType.HEIC, AttachmentType.detect(heic).orElseThrow());

        BusinessException e = assertThrows(BusinessException.class, () -> app.attachments.attach(repair.id(),
                "facture.pdf", "MZ ceci est un programme, pas un PDF".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(e.getMessage().contains("Format non pris en charge"));
        assertThrows(BusinessException.class, () -> app.attachments.attach(repair.id(), "vide.pdf", new byte[0]));
    }

    @Test
    void attachListReadAndDelete() {
        Attachment invoice = app.attachments.attach(repair.id(), "C:\\Users\\moi\\Factures\\facture.PDF", pdf());
        app.attachments.attach(repair.id(), "photo.png", png());

        assertEquals("facture.pdf", invoice.fileName(), "sans dossier, extension du format reel");
        assertEquals(AttachmentType.PDF, invoice.type());
        assertEquals(pdf().length, invoice.size());
        assertEquals(TODAY.atStartOfDay(), invoice.addedAt());
        assertEquals(List.of("facture.pdf", "photo.png"),
                app.attachments.list(repair.id()).stream().map(Attachment::fileName).toList());
        assertArrayEquals(pdf(), app.attachments.content(invoice.id()));
        assertEquals(Map.of(repair.id(), 2), app.attachments.counts(List.of(repair.id(), 999L)));
        assertEquals(new AttachmentService.Usage(2, pdf().length + png().length), app.attachments.usage());

        app.attachments.delete(invoice.id());
        assertEquals(1, app.attachments.list(repair.id()).size());
        assertThrows(BusinessException.class, () -> app.attachments.content(invoice.id()));
    }

    @Test
    void limitsAreEnforced() {
        byte[] big = Arrays.copyOf(pdf(), (int) AttachmentService.MAX_SIZE + 1);
        assertThrows(BusinessException.class, () -> app.attachments.attach(repair.id(), "gros.pdf", big));
        assertThrows(BusinessException.class, () -> app.attachments.attach(9999L, "x.pdf", pdf()),
                "operation inconnue");
        for (int i = 0; i < AttachmentService.MAX_PER_TRANSACTION; i++) {
            app.attachments.attach(repair.id(), "ticket" + i + ".pdf", pdf());
        }
        assertThrows(BusinessException.class, () -> app.attachments.attach(repair.id(), "un de trop.pdf", pdf()));
    }

    @Test
    void namesAreCleaned() {
        assertEquals("ticket.jpg", AttachmentService.cleanName("../../ticket.jpeg", AttachmentType.JPEG));
        assertEquals("justificatif.pdf", AttachmentService.cleanName("  ", AttachmentType.PDF));
        assertEquals("ab.pdf", AttachmentService.cleanName("a<b>?.pdf", AttachmentType.PDF));
        assertEquals(AttachmentService.MAX_NAME_LENGTH,
                AttachmentService.cleanName("x".repeat(500) + ".pdf", AttachmentType.PDF).length());
    }

    @Test
    void attachmentsGoAwayWithTheirTransaction() {
        app.attachments.attach(repair.id(), "facture.pdf", pdf());
        app.transactions.delete(repair.id());
        assertEquals(new AttachmentService.Usage(0, 0), app.attachments.usage());
    }
}
