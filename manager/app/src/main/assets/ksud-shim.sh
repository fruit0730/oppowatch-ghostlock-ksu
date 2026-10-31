#!/system/bin/sh
# ksud compatibility shim for the OWW212 GhostLock jailbreak.
# Invoked by the manager as: /system/bin/sh ksud-shim.sh <args>
# It implements the small ksud surface the KernelSU manager uses.

MODDIR=${KSUW_MODDIR:-/data/adb/modules}
FEATDIR=${KSUW_FEATDIR:-/data/adb/ksuwatch/features}
# GhostLock root has no CAP_DAC_OVERRIDE: /data/local/tmp is shell-owned 0771 and
# cannot be written. /data/adb is root owned, and this dir is created by root.
TMPBASE=${KSUW_TMPBASE:-/data/adb/ksuwatch}

json_escape() {
  printf '%s' "$1" | tr -d '\r\n' | sed 's/\\/\\\\/g; s/"/\\"/g'
}

get_prop() {
  sed -n "s/^$2=//p" "$1" 2>/dev/null | head -n 1
}

feat_get() {
  if [ -f "$FEATDIR/$1" ]; then
    cat "$FEATDIR/$1" 2>/dev/null
  else
    case "$1" in
      sulog|adb_root|shell_su_adb_only) echo 0 ;;
      *) echo 1 ;;
    esac
  fi
}

feat_set() {
  mkdir -p "$FEATDIR" 2>/dev/null || return 1
  printf '%s' "$2" > "$FEATDIR/$1"
}

list_modules() {
  printf '['
  first=1
  for d in "$MODDIR"/*; do
    [ -d "$d" ] || continue
    p="$d/module.prop"
    [ -f "$p" ] || continue
    id=$(basename "$d")
    name=$(get_prop "$p" name); [ -n "$name" ] || name="$id"
    author=$(get_prop "$p" author); [ -n "$author" ] || author="Unknown"
    version=$(get_prop "$p" version); [ -n "$version" ] || version="Unknown"
    vc=$(get_prop "$p" versionCode)
    case "$vc" in ''|*[!0-9]*) vc=0 ;; esac
    desc=$(get_prop "$p" description)
    update_json=$(get_prop "$p" updateJson)
    meta=$(get_prop "$p" metamodule); [ "$meta" = "1" ] || meta=0
    enabled=true; [ -e "$d/disable" ] && enabled=false
    remove=false; [ -e "$d/remove" ] && remove=true
    update=false; [ -e "$d/update" ] && update=true
    web=false; [ -d "$d/webroot" ] && web=true
    action=false; [ -f "$d/action.sh" ] && action=true
    [ "$first" -eq 1 ] || printf ','
    first=0
    printf '{"id":"%s","name":"%s","author":"%s","version":"%s","versionCode":%s,"description":"%s","enabled":%s,"update":%s,"remove":%s,"updateJson":"%s","web":%s,"action":%s,"metamodule":%s,"actionIcon":"","webuiIcon":"","preinit":""}' \
      "$(json_escape "$id")" \
      "$(json_escape "$name")" \
      "$(json_escape "$author")" \
      "$(json_escape "$version")" \
      "$vc" \
      "$(json_escape "$desc")" \
      "$enabled" "$update" "$remove" "$(json_escape "$update_json")" \
      "$web" "$action" "$meta"
  done
  printf ']\n'
}

install_module() {
  zip="$1"
  if [ ! -f "$zip" ]; then
    echo "! module zip not found: $zip" >&2
    return 1
  fi
  tmp="$TMPBASE/ksuwatch-install-$$"
  rm -rf "$tmp"
  mkdir -p "$tmp" || return 1
  /system/bin/unzip -o -q "$zip" -d "$tmp" >/dev/null 2>&1
  if [ ! -f "$tmp/module.prop" ]; then
    echo "! invalid module: module.prop missing" >&2
    rm -rf "$tmp"
    return 1
  fi
  id=$(get_prop "$tmp/module.prop" id)
  case "$id" in
    [A-Za-z]*) ;;
    *) echo "! invalid module id: $id" >&2; rm -rf "$tmp"; return 1 ;;
  esac
  case "$id" in
    *[!A-Za-z0-9._-]*) echo "! invalid module id: $id" >&2; rm -rf "$tmp"; return 1 ;;
  esac
  target="$MODDIR/$id"
  mkdir -p "$MODDIR" 2>/dev/null
  if [ -d "$target" ]; then
    touch "$target/update" 2>/dev/null
  fi
  mkdir -p "$target" || { rm -rf "$tmp"; return 1; }
  cp -R "$tmp"/. "$target"/ || { rm -rf "$tmp"; return 1; }
  rm -rf "$tmp"
  find "$target" -type d -exec chmod 0755 {} \; 2>/dev/null
  find "$target" -type f -exec chmod 0644 {} \; 2>/dev/null
  find "$target" -name '*.sh' -exec chmod 0755 {} \; 2>/dev/null
  echo "installed $id"
  return 0
}

cmd="$1"
[ -n "$cmd" ] || exit 1
shift

case "$cmd" in
  module)
    sub="$1"; shift
    case "$sub" in
      list)
        list_modules
        ;;
      install)
        install_module "$1"
        ;;
      enable)
        rm -f "$MODDIR/$1/disable"
        ;;
      disable)
        touch "$MODDIR/$1/disable"
        ;;
      uninstall)
        id="$1"
        case "$id" in
          [A-Za-z]*) ;;
          *) echo "! invalid module id" >&2; exit 1 ;;
        esac
        case "$id" in
          *[!A-Za-z0-9._-]*) echo "! invalid module id" >&2; exit 1 ;;
        esac
        # No boot hook exists in this port, so remove now instead of leaving a
        # `remove` marker that nothing would ever consume.
        rm -rf "$MODDIR/$id"
        ;;
      undo-uninstall)
        rm -f "$MODDIR/$1/remove"
        ;;
      action)
        d="$MODDIR/$1"
        [ -f "$d/action.sh" ] || { echo "! no action.sh in $1" >&2; exit 1; }
        cd "$d" || exit 1
        MODDIR="$d" /system/bin/sh "$d/action.sh"
        ;;
      *)
        echo "! unsupported module command: $sub" >&2
        exit 1
        ;;
    esac
    ;;
  feature)
    sub="$1"; shift
    case "$sub" in
      check)
        echo 1
        ;;
      get)
        name="$1"
        [ "$name" = "--config" ] && name="$2"
        echo "Value: $(feat_get "$name")"
        ;;
      set)
        feat_set "$1" "$2" || exit 1
        ;;
      save)
        ;;
      *)
        ;;
    esac
    ;;
  boot-info)
    sub="$1"
    case "$sub" in
      current-kmi) echo "android11-5.4" ;;
      supported-kmis) ;;
      is-ab-device) echo "false" ;;
      default-partition) echo "boot" ;;
      slot-suffix) ;;
      available-partitions) echo "boot" ;;
      *) ;;
    esac
    ;;
  profile|sepolicy)
    ;;
  install)
    ;;
  soft-reboot|boot-restore|boot-patch|uninstall)
    echo "! not supported in GhostLock jailbreak mode" >&2
    exit 1
    ;;
  *)
    echo "! unsupported ksud command: $cmd" >&2
    exit 1
    ;;
esac

exit 0
