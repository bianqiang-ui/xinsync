"""配置门禁：解析仓库内所有 application.yml，检出语法错误与重复键（YAML 重复键会静默覆盖，是启动假成功的常见来源）。

用法：python devops/checks/check_yaml.py [仓库根目录，默认为本文件所在仓库]
"""
import io
import os
import sys

import yaml


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
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
