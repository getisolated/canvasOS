#!/usr/bin/env bash
# Generate canvasOS's release keystore and print what GitHub Actions needs to sign with it.
#
#     tools/make-keystore.sh [-o canvasos-release.jks] [-b 4096] [--properties]
#
# Needs only `keytool` (any JDK). Run it ONCE, for the whole life of the project: every APK that
# ever updates an installed canvasOS must carry this same signature, and a lost key means every
# user has to uninstall (and lose their settings) to switch to a new one.
#
# The keystore is PKCS12, where the key password and the store password are the same value. The
# two secrets stay separate (CANVASOS_KEYSTORE_PASSWORD and CANVASOS_KEY_PASSWORD) so the workflow
# and build.gradle.kts do not care which keystore format is used.
#
# Passwords: the CANVASOS_KEYSTORE_PASSWORD environment variable if set, else asked on the
# terminal (empty answer = generate a strong one), else generated. They never appear on a
# command line while keytool runs.
set -eu

REPO="getisolated/canvasOS"
ALIAS="canvasos"
DNAME="CN=canvasOS, O=getisolated"
VALIDITY_DAYS=10000
OUT="canvasos-release.jks"
BITS=4096
WRITE_PROPERTIES=ask

usage() {
  cat <<EOF
Usage : tools/make-keystore.sh [-o fichier.jks] [-b 2048|4096] [--properties]

  -o, --output FILE   keystore à créer (défaut : canvasos-release.jks dans le dossier courant)
  -b, --bits N        taille de la clé RSA, 2048 ou 4096 (défaut : 4096)
      --properties    écrit aussi keystore.properties à la racine du dépôt, sans poser la question
  -h, --help          cette aide
EOF
}

die() { echo "Erreur : $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    -o|--output) [ $# -ge 2 ] || die "$1 attend un argument"; OUT="$2"; shift 2 ;;
    -b|--bits) [ $# -ge 2 ] || die "$1 attend un argument"; BITS="$2"; shift 2 ;;
    --properties) WRITE_PROPERTIES=yes; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; die "option inconnue : $1" ;;
  esac
done
case "$BITS" in 2048|4096) ;; *) die "--bits doit valoir 2048 ou 4096" ;; esac

# keytool: PATH first, then JAVA_HOME. macOS ships a /usr/bin/keytool stub that fails without a JDK.
KEYTOOL=""
if command -v keytool >/dev/null 2>&1 && keytool -help >/dev/null 2>&1; then
  KEYTOOL="keytool"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/keytool" ]; then
  KEYTOOL="$JAVA_HOME/bin/keytool"
else
  # Homebrew's openjdk formulae are keg-only: nothing of theirs is on the PATH.
  for d in /opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/openjdk /usr/local/opt/openjdk@17 /usr/local/opt/openjdk; do
    if [ -x "$d/bin/keytool" ]; then KEYTOOL="$d/bin/keytool"; break; fi
  done
fi
[ -n "$KEYTOOL" ] || die "keytool introuvable : installez un JDK 17 (macOS : brew install openjdk@17 ; Debian/Ubuntu : apt install openjdk-17-jdk-headless) ou renseignez JAVA_HOME."

[ ! -e "$OUT" ] || die "$OUT existe déjà ; je ne l'écrase jamais (ce serait perdre la clé). Choisissez un autre nom avec -o."

# Absolute path of $1 even though the file may not exist yet. `pwd -W` gives a Windows path under
# Git Bash, which Gradle needs; other shells fall back to plain pwd.
abs_path() {
  dir=$(cd "$(dirname "$1")" && { pwd -W 2>/dev/null || pwd; })
  echo "$dir/$(basename "$1")"
}

interactive=no
[ -t 0 ] && [ -t 1 ] && interactive=yes

random_password() {
  # tr is killed by SIGPIPE once head has its 32 characters: that is expected.
  LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom 2>/dev/null | head -c 32 || true
}

PASSWORD="${CANVASOS_KEYSTORE_PASSWORD:-}"
if [ -z "$PASSWORD" ] && [ "$interactive" = yes ]; then
  while :; do
    printf "Mot de passe du keystore (6 caractères minimum, Entrée = en générer un) : "
    read -r -s PASSWORD; echo
    [ -n "$PASSWORD" ] || break
    printf "Confirmez : "
    read -r -s CONFIRM; echo
    [ "$PASSWORD" = "$CONFIRM" ] && break
    echo "Les deux saisies diffèrent, recommencez."
  done
fi
[ -n "$PASSWORD" ] || PASSWORD=$(random_password)
[ "${#PASSWORD}" -ge 6 ] || die "le mot de passe doit faire au moins 6 caractères."
# keystore.properties treats a backslash as an escape and drops edge spaces.
case "$PASSWORD" in
  *\\*|' '*|*' ') die "le mot de passe ne doit contenir ni antislash ni espace au début ou à la fin." ;;
esac

echo "Création de $OUT (RSA $BITS, alias $ALIAS, $VALIDITY_DAYS jours)…"
# keytool's progress chatter goes to stderr: keep it only if the command fails.
if ! KEYTOOL_LOG=$(STOREPASS="$PASSWORD" "$KEYTOOL" -genkeypair \
  -keystore "$OUT" -storetype PKCS12 -storepass:env STOREPASS \
  -alias "$ALIAS" -keyalg RSA -keysize "$BITS" -validity "$VALIDITY_DAYS" \
  -dname "$DNAME" 2>&1); then
  echo "$KEYTOOL_LOG" >&2
  die "keytool a échoué."
fi
chmod 600 "$OUT" 2>/dev/null || true

FINGERPRINT=$(STOREPASS="$PASSWORD" "$KEYTOOL" -J-Duser.language=en -list -keystore "$OUT" \
  -storepass:env STOREPASS -alias "$ALIAS" | grep -i 'SHA.\{0,2\}256' | sed 's/^[^:]*: *//' | head -n 1)
B64=$(base64 < "$OUT" | tr -d '\n')
# Shell-quoted for the gh commands below (a no-op for the generated alphanumeric passwords).
PASSWORD_Q=$(printf '%q' "$PASSWORD")

cat <<EOF

Keystore créé : $(abs_path "$OUT")
Empreinte SHA-256 du certificat : ${FINGERPRINT:-(illisible)}
  (à comparer plus tard avec : apksigner verify --print-certs canvasOS.apk)

Secrets à créer dans GitHub (Settings > Secrets and variables > Actions du dépôt $REPO) :

  CANVASOS_KEYSTORE_B64      $B64
  CANVASOS_KEYSTORE_PASSWORD $PASSWORD
  CANVASOS_KEY_ALIAS         $ALIAS
  CANVASOS_KEY_PASSWORD      $PASSWORD

Ou, en une fois, avec la CLI GitHub (gh auth login avec un compte qui administre le dépôt) :

gh secret set CANVASOS_KEYSTORE_B64 --repo $REPO --body "$B64"
gh secret set CANVASOS_KEYSTORE_PASSWORD --repo $REPO --body $PASSWORD_Q
gh secret set CANVASOS_KEY_ALIAS --repo $REPO --body "$ALIAS"
gh secret set CANVASOS_KEY_PASSWORD --repo $REPO --body $PASSWORD_Q

IMPORTANT
  - Gardez $(basename "$OUT") et le mot de passe dans un endroit sûr et sauvegardé (gestionnaire de
    mots de passe, coffre chiffré) : sans cette clé, plus aucune mise à jour ne s'installera par-dessus
    une version existante.
  - Ne les commitez jamais (*.jks, *.keystore et keystore.properties sont déjà dans .gitignore).
  - Ce terminal vient d'afficher les secrets : videz son historique et son défilement si besoin.
EOF

if [ "$WRITE_PROPERTIES" = ask ] && [ "$interactive" = yes ]; then
  printf "\nÉcrire keystore.properties à la racine du dépôt, pour signer vos builds locaux avec cette clé ? [o/N] "
  read -r ANSWER
  case "$ANSWER" in o|O|y|Y|oui|OUI|yes|YES) WRITE_PROPERTIES=yes ;; *) WRITE_PROPERTIES=no ;; esac
fi

if [ "$WRITE_PROPERTIES" = yes ]; then
  ROOT=$(cd "$(dirname "$0")/.." && { pwd -W 2>/dev/null || pwd; })
  PROPS="$ROOT/keystore.properties"
  if [ -e "$PROPS" ]; then
    echo "$PROPS existe déjà : laissé tel quel. Valeurs à y mettre :" >&2
    echo "  storeFile=$(abs_path "$OUT")  keyAlias=$ALIAS  (+ les mots de passe ci-dessus)" >&2
  else
    # Backslashes are escapes in .properties files; the paths here use forward slashes.
    ( umask 077
      cat > "$PROPS" <<EOF
storeFile=$(abs_path "$OUT")
storePassword=$PASSWORD
keyAlias=$ALIAS
keyPassword=$PASSWORD
EOF
    )
    echo
    echo "Écrit : $PROPS (lu par app/build.gradle.kts pour les builds release locaux)."
  fi
fi
