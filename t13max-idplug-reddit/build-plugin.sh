#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
DEFAULT_IDEA_PATH='D:\Program Files\JetBrains\IntelliJ IDEA 2026.1'
IDEA_INPUT="${IDEA_PATH:-$DEFAULT_IDEA_PATH}"
SKIP_CLEAN=0

# 显示脚本使用说明。
show_usage() {
    printf '%s\n' '用法：bash build-plugin.sh [IDEA安装目录] [--skip-clean]'
    printf '%s\n' '示例：bash build-plugin.sh "/d/Program Files/JetBrains/IntelliJ IDEA 2026.1"'
    printf '%s\n' '也可设置环境变量：IDEA_PATH="D:\Program Files\JetBrains\IntelliJ IDEA 2026.1"'
}

# 输出错误信息并终止脚本。
fail() {
    printf '错误：%s\n' "$1" >&2
    exit 1
}

# 解析 IDEA 路径和可选参数。
parse_arguments() {
    local positional_path=''
    while (($# > 0)); do
        case "$1" in
            --skip-clean)
                SKIP_CLEAN=1
                ;;
            -h|--help)
                show_usage
                exit 0
                ;;
            -* )
                fail "未知参数：$1"
                ;;
            *)
                [[ -z "$positional_path" ]] || fail '只能指定一个 IDEA 安装目录'
                positional_path="$1"
                ;;
        esac
        shift
    done
    if [[ -n "$positional_path" ]]; then
        IDEA_INPUT="$positional_path"
    fi
}

# 校验当前 Shell 是否为 Windows Git Bash。
require_git_bash() {
    local system_name
    system_name="$(uname -s)"
    case "$system_name" in
        MINGW*|MSYS*|CYGWIN*) ;;
        *) fail '该脚本面向 Windows Git Bash，不支持直接在 WSL 中使用 Windows 版 IDEA/JBR' ;;
    esac
    command -v cygpath >/dev/null 2>&1 || fail '未找到 cygpath，请使用 Git Bash 运行脚本'
}

# 将 IDEA 路径转换为 Git Bash 和 Gradle 可识别的格式。
resolve_idea_paths() {
    IDEA_POSIX_PATH="$(cygpath -u "$IDEA_INPUT")"
    IDEA_GRADLE_PATH="$(cygpath -m "$IDEA_INPUT")"
    [[ -d "$IDEA_POSIX_PATH" ]] || fail "IDEA 目录不存在：$IDEA_INPUT"
    [[ -x "$IDEA_POSIX_PATH/jbr/bin/java.exe" ]] || fail "未找到 IDEA 自带 JBR：$IDEA_POSIX_PATH/jbr/bin/java.exe"
}

# 执行 Gradle 打包与插件结构检查。
build_plugin() {
    local tasks=()
    if ((SKIP_CLEAN == 0)); then
        tasks+=(clean)
    fi
    tasks+=(test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure)
    export JAVA_HOME="$IDEA_POSIX_PATH/jbr"
    cd "$SCRIPT_DIR"
    printf 'IDEA：%s\n' "$IDEA_GRADLE_PATH"
    printf 'JBR：%s\n' "$JAVA_HOME"
    ./gradlew "${tasks[@]}" "-PideaPath=$IDEA_GRADLE_PATH"
}

# 输出最新安装包的位置、大小与 SHA-256。
print_artifact() {
    local distribution_dir="$SCRIPT_DIR/build/distributions"
    local zip_file
    [[ -d "$distribution_dir" ]] || fail '没有找到 build/distributions 输出目录'
    zip_file="$(find "$distribution_dir" -maxdepth 1 -type f -name '*.zip' -printf '%T@ %p\n' | sort -nr | head -n 1 | cut -d' ' -f2-)"
    [[ -n "$zip_file" && -f "$zip_file" ]] || fail '没有找到插件 ZIP 安装包'
    printf '\n打包成功\n'
    printf '安装包：%s\n' "$(cygpath -w "$zip_file")"
    printf '大小：%s 字节\n' "$(wc -c < "$zip_file" | tr -d ' ')"
    printf 'SHA-256：%s\n' "$(sha256sum "$zip_file" | awk '{print $1}')"
}

# 执行脚本主流程。
main() {
    parse_arguments "$@"
    require_git_bash
    resolve_idea_paths
    build_plugin
    print_artifact
}

main "$@"
