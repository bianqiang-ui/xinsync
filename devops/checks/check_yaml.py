"""配置门禁：解析仓库内所有 application.yml，检出语法错误与重复键（YAML 重复键会静默覆盖，是启动假成功的常见来源）。

用法：python devops/checks/check_yaml.py [仓库根目录，默认为本文件所在仓库]
"""
import io
import os
import sys

try:
    import yaml
except ImportError:
    # PyYAML 不在 JDK8 构建镜像里（那里只有 python3 标准库）。这条不是"环境问题可以忽略"：
    # 缺库时必须**响亮地**失败并给出下一步，绝不能让门禁在 traceback 里静默消失。
    sys.stderr.write(
        "FAIL 缺少 PyYAML，无法解析 YAML。\n"
        "   任选其一：pip install pyyaml  或  在宿主 python（装有 PyYAML）上跑本门禁：\n"
        "   python3 devops/checks/check_yaml.py\n")
    sys.exit(1)


class DuplicateKeyLoader(yaml.SafeLoader):
    pass


def _construct_mapping(loader, node, deep=False):
    seen = {}
    for key_node, _ in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if isinstance(key, str) and key in seen:
            raise ValueError(
                "duplicate key '%s' at line %d (first seen at line %d)"
                % (key, key_node.start_mark.line + 1, seen[key])
            )
        seen[key] = key_node.start_mark.line + 1
    return yaml.SafeLoader.construct_mapping(loader, node, deep)


DuplicateKeyLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _construct_mapping
)


def main():
    default_root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    root = sys.argv[1] if len(sys.argv) > 1 else default_root
    targets = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in ("target", ".git", "node_modules")]
        for name in filenames:
            if name.endswith((".yml", ".yaml")):
                targets.append(os.path.join(dirpath, name))

    failed = 0
    for path in sorted(targets):
        try:
            with io.open(path, encoding="utf-8") as handle:
                list(yaml.load_all(handle, Loader=DuplicateKeyLoader))
            print("OK   %s" % os.path.relpath(path, root))
        except Exception as exc:
            failed += 1
            print("FAIL %s -> %s" % (os.path.relpath(path, root), exc))

    print("checked=%d failed=%d" % (len(targets), failed))
    if failed:
        print("FAIL: %d 份 YAML 有问题（共查 %d 份）" % (failed, len(targets)))
        return 1
    if not targets:
        # 一份都没查到 = 扫描根走空了（改名/移动目录都会这样），不能算通过
        print("FAIL: 没有发现任何 .yml/.yaml，扫描根 %s 是否还是仓库根？" % root)
        return 1
    print("PASS: %d 份 YAML 语法与同层重复键检查通过" % len(targets))
    return 0


if __name__ == "__main__":
    sys.exit(main())
