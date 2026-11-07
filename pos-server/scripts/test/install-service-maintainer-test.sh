#!/bin/bash
# Tests for the maintainer-account part of install-service.sh.
# Runs against a temp install dir with a stubbed `java` and `systemctl`; never touches system paths.
#   bash pos-server/scripts/test/install-service-maintainer-test.sh
# The stub java records its argv and stdin so the tests can prove the password never appears on a
# command line, and returns a hash containing `$` characters.

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="${HERE}/../install-service.sh"
PASS=0
FAIL=0
HASH='$2b$12$'"$(printf 'a%.0s' $(seq 1 53))"

ok()   { PASS=$((PASS + 1)); echo "  ok   $1"; }
bad()  { FAIL=$((FAIL + 1)); echo "  FAIL $1"; }
check() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }

new_env() {
    TMP="$(mktemp -d)"
    mkdir -p "${TMP}/bin"
    : > "${TMP}/pos-server.jar"
    printf 'JWT_SECRET=abc+/=\nJWT_DEVELOPMENT_MODE=false\n' > "${TMP}/pos-server.env"
    chmod 600 "${TMP}/pos-server.env"
    cat > "${TMP}/bin/java" <<EOF
#!/bin/bash
echo "\$@" > "${TMP}/java.argv"
IFS= read -r p1; IFS= read -r p2
printf '%s\n%s\n' "\$p1" "\$p2" > "${TMP}/java.stdin"
if [ "\${#p1}" -lt 12 ]; then echo "The password must be at least 12 characters long." >&2; exit 1; fi
cat "${TMP}/hashline"
EOF
    printf "MAINTAINER_PASSWORD_HASH='%s'\\n" "${HASH}" > "${TMP}/hashline"
    chmod +x "${TMP}/bin/java"
    cat > "${TMP}/bin/systemctl" <<EOF
#!/bin/bash
echo "\$@" >> "${TMP}/systemctl.log"
[ "\$1" = "is-active" ] && exit 0
exit 0
EOF
    chmod +x "${TMP}/bin/systemctl"
    ENV="${TMP}/pos-server.env"
}

# Each test runs in a subshell with the script sourced.
run() {
    ( PATH="${TMP}/bin:${PATH}"; set +e +u
      # shellcheck source=../install-service.sh
      . "${SCRIPT}"; set +e +u
      "$@" )
}
interactive() { is_interactive() { return 0; }; }
mode() { stat -f '%Lp' "$1" 2>/dev/null || stat -c '%a' "$1"; }

echo "new install: yes"
new_env
INPUT="$(printf 'y\nwartung\nCorrect-Horse-1\nCorrect-Horse-1\n')"
( PATH="${TMP}/bin:${PATH}"; . "${SCRIPT}"; set +e +u; interactive
  configure_maintainer_account "${ENV}" "${TMP}" <<< "${INPUT}" ) >/dev/null 2>&1
check "username written" "grep -qx 'MAINTAINER_USERNAME=wartung' '${ENV}'"
check 'hash single-quoted, $ intact' "grep -qxF \"MAINTAINER_PASSWORD_HASH='\${HASH}'\" '${ENV}'"
check "mode stays 600" "[ \"\$(mode '${ENV}')\" = 600 ]"
check "JWT secret preserved" "grep -qxF 'JWT_SECRET=abc+/=' '${ENV}'"
check "backup exists" "ls '${ENV}'.maintainer-bak-* >/dev/null 2>&1"
check "password not in java argv" "! grep -q 'Correct-Horse' '${TMP}/java.argv'"
check "password not in env file" "! grep -q 'Correct-Horse' '${ENV}'"

echo "new install: no (asked once)"
new_env
( PATH="${TMP}/bin:${PATH}"; . "${SCRIPT}"; set +e +u; interactive
  configure_maintainer_account "${ENV}" "${TMP}" <<< "n" ) >/dev/null 2>&1
check "no account written" "! grep -q '^MAINTAINER_' '${ENV}'"
check "marker recorded" "grep -q 'maintainer-setup-offered' '${ENV}'"
OUT="$( ( PATH="${TMP}/bin:${PATH}"; . "${SCRIPT}"; set +e +u; interactive
  configure_maintainer_account "${ENV}" "${TMP}" <<< "y" ) 2>&1 )"
check "second run does not ask again" "! echo \"\${OUT}\" | grep -q 'Set up a maintainer'"

echo "no TTY, no flags: silent skip"
new_env
OUT="$( ( PATH="${TMP}/bin:${PATH}"; . "${SCRIPT}"; set +e +u
  configure_maintainer_account "${ENV}" "${TMP}" < /dev/null ) 2>&1 )"
check "nothing printed" "[ -z \"\${OUT}\" ]"
check "env untouched" "! grep -q '^MAINTAINER_' '${ENV}'"

echo "update: existing keys untouched"
new_env
printf "MAINTAINER_USERNAME=old\nMAINTAINER_PASSWORD_HASH='%s'\n" "${HASH}" >> "${ENV}"
BEFORE="$(cat "${ENV}")"
OUT="$( ( PATH="${TMP}/bin:${PATH}"; . "${SCRIPT}"; set +e +u; interactive
  MAINTAINER_NAME_ARG=other; MAINTAINER_PASSWORD_STDIN=1
  configure_maintainer_account "${ENV}" "${TMP}" <<< "Another-Pass-123" ) 2>&1 )"
check "file identical" "[ \"\$(cat '${ENV}')\" = \"\${BEFORE}\" ]"
check "says so" "echo \"\${OUT}\" | grep -q 'leaving it untouched'"

echo "update: no keys -> offered, scripted via stdin"
new_env
( PATH="${TMP}/bin:${PATH}"; . "${SCRIPT}"; set +e +u
  MAINTAINER_NAME_ARG=ops; MAINTAINER_PASSWORD_STDIN=1
  configure_maintainer_account "${ENV}" "${TMP}" <<< "Stdin-Password-9" ) >/dev/null 2>&1
check "scripted account written" "grep -qx 'MAINTAINER_USERNAME=ops' '${ENV}'"
check "stub received password on stdin only" "grep -q 'Stdin-Password-9' '${TMP}/java.stdin' && ! grep -q 'Stdin' '${TMP}/java.argv'"

echo "--maintainer replace / --maintainer-off"
( PATH="${TMP}/bin:${PATH}"; . "${SCRIPT}"; set +e +u
  MAINTAINER_NAME_ARG=ops2; MAINTAINER_PASSWORD_STDIN=1
  maintainer_command set "${TMP}" <<< "Replacement-Pass-1" ) >/dev/null 2>&1
check "replaced username" "grep -qx 'MAINTAINER_USERNAME=ops2' '${ENV}' && ! grep -q '=ops\$' '${ENV}'"
check "exactly one of each key" "[ \"\$(grep -c '^MAINTAINER_' '${ENV}')\" = 2 ]"
check "service restarted" "grep -q 'restart pos-server' '${TMP}/systemctl.log'"
check "mode still 600" "[ \"\$(mode '${ENV}')\" = 600 ]"
( PATH="${TMP}/bin:${PATH}"; . "${SCRIPT}"; set +e +u
  maintainer_command off "${TMP}" ) >/dev/null 2>&1
check "off removes both keys" "! grep -q '^MAINTAINER_' '${ENV}'"
check "off keeps JWT secret" "grep -qxF 'JWT_SECRET=abc+/=' '${ENV}'"
check "at most 3 backups kept" "[ \"\$(ls '${ENV}'.maintainer-bak-* | wc -l | tr -d ' ')\" -le 3 ]"

echo "rejected short password"
new_env
OUT="$( ( PATH="${TMP}/bin:${PATH}"; . "${SCRIPT}"; set +e +u
  MAINTAINER_NAME_ARG=ops; MAINTAINER_PASSWORD_STDIN=1
  write_maintainer_account "${ENV}" "${TMP}" <<< "short" ) 2>&1 )"
check "refused with message" "echo \"\${OUT}\" | grep -q 'at least 12'"
check "env untouched" "! grep -q '^MAINTAINER_' '${ENV}'"
OUT="$( ( PATH="${TMP}/bin:${PATH}"; . "${SCRIPT}"; set +e +u
  MAINTAINER_NAME_ARG=ops; MAINTAINER_PASSWORD_STDIN=1
  write_maintainer_account "${ENV}" "${TMP}" <<< "$(printf 'x%.0s' $(seq 1 73))" ) 2>&1 )"
check "over 72 bytes refused" "echo \"\${OUT}\" | grep -q 'at most 72'"

echo "bad username / non-BCrypt output"
new_env
OUT="$( ( PATH="${TMP}/bin:${PATH}"; . "${SCRIPT}"; set +e +u
  MAINTAINER_NAME_ARG="a b'c"; MAINTAINER_PASSWORD_STDIN=1
  write_maintainer_account "${ENV}" "${TMP}" <<< "Valid-Password-12" ) 2>&1 )"
check "username with quote/space refused" "echo \"\${OUT}\" | grep -q 'may only contain'"
printf '#!/bin/bash\ncat >/dev/null\necho "MAINTAINER_PASSWORD_HASH='"'"'notabcrypt'"'"'"\n' > "${TMP}/bin/java"
OUT="$( ( PATH="${TMP}/bin:${PATH}"; . "${SCRIPT}"; set +e +u
  MAINTAINER_NAME_ARG=ops; MAINTAINER_PASSWORD_STDIN=1
  write_maintainer_account "${ENV}" "${TMP}" <<< "Valid-Password-12" ) 2>&1 )"
check "non-BCrypt jar output never written" "echo \"\${OUT}\" | grep -q 'did not return a BCrypt' && ! grep -q '^MAINTAINER_' '${ENV}'"

echo
echo "passed: ${PASS}  failed: ${FAIL}"
[ "${FAIL}" = 0 ]
