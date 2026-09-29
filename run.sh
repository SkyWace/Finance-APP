#!/usr/bin/env bash
# Construit puis lance FinanceApp.
#
# Usage :
#   ./run.sh              construit (sans les tests) puis lance
#   ./run.sh --test       execute d'abord tous les tests
#   ./run.sh --no-build   relance sans reconstruire
#
# Donnees : emplacement standard du systeme ; pour un essai isole :
#   JAVA_OPTS="-Dapp.data-dir=/tmp/financeapp-essai" ./run.sh
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
say() { echo "[FinanceApp] $*"; }

command -v java >/dev/null 2>&1 || { say "Java 21+ est requis (https://adoptium.net)."; exit 1; }
major="$(java -version 2>&1 | awk -F'"' '/version/ {print $2; exit}' | cut -d. -f1)"
if [ "${major:-0}" -lt 21 ] 2>/dev/null; then
    say "Java 21+ est requis (version detectee : ${major:-inconnue})."
    exit 1
fi

build=1
tests="-DskipTests"
for arg in "$@"; do
    case "$arg" in
        --test) tests="" ;;
        --no-build) build=0 ;;
        *) say "Option inconnue : $arg"; exit 2 ;;
    esac
done

jar="financeapp-desktop/target/financeapp-desktop.jar"
if [ "$build" -eq 1 ] || [ ! -f "$jar" ]; then
    command -v mvn >/dev/null 2>&1 || { say "Maven 3.9+ est requis pour construire le projet."; exit 1; }
    say "Construction..."
    mvn -q -B package $tests
fi

say "Lancement..."
exec java ${JAVA_OPTS:-} -jar "$jar"
