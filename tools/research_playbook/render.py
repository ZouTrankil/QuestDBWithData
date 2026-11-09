"""离线执行全部教学笔记本，并导出可直接打开的HTML。"""
from __future__ import annotations

import json
from pathlib import Path
import sys
from datetime import datetime, timezone
import html
import re
import os
from urllib.parse import urlsplit

import nbformat
from nbclient import NotebookClient
from nbconvert import HTMLExporter
from jupyter_client import KernelManager
from jupyter_client.kernelspec import KernelSpecManager

from .data import ROOT, data_root, sha, write_bounded


def main():
    output = ROOT / "var/research-playbook/html"
    kernels = ROOT / "var/research-playbook/jupyter/kernels"
    spec = kernels / "playbook"
    spec.mkdir(parents=True, exist_ok=True)
    (spec / "kernel.json").write_text(json.dumps({
        "argv": [sys.executable, "-m", "ipykernel_launcher", "-f", "{connection_file}"],
        "display_name": "研究Playbook", "language": "python",
        "env": {"MPLBACKEND": "module://matplotlib_inline.backend_inline"},
    }), encoding="utf8")
    records = []
    exporter = HTMLExporter(template_name="lab")
    for path in sorted((ROOT / "research/notebooks").glob("*.ipynb")):
        before = sha(path)
        notebook = nbformat.read(path, as_version=4)
        # 使用当前项目环境，避免系统同名kernel悄悄切换依赖版本。
        manager = KernelManager(kernel_name="playbook", transport="ipc", kernel_spec_manager=KernelSpecManager(kernel_dirs=[str(kernels)]))
        try:
            NotebookClient(notebook, km=manager, timeout=180,
                           resources={"metadata": {"path": str(ROOT)}}, allow_errors=False).execute()
        finally:
            if manager.has_kernel:
                manager.shutdown_kernel(now=True)
        if sha(path) != before:
            raise RuntimeError(f"笔记本被其他窗口修改，保留对方内容：{path}")
        notebook.metadata.kernelspec = {"name": "python3", "display_name": "Python 3", "language": "python"}
        body = nbformat.writes(notebook).encode("utf8")
        write_bounded(ROOT / "research", path.relative_to(ROOT / "research"), body, 50_000_000)
        rendered, _ = exporter.from_notebook_node(notebook)
        # HTML在var下，教材在research下；导出时重定位本地链接，保留外部来源地址。
        def relocate(match):
            href = match.group(1)
            parts = urlsplit(href)
            if parts.scheme or parts.netloc or not parts.path or parts.path.startswith("/"):
                return match.group(0)
            relative = os.path.relpath((path.parent / parts.path).resolve(), output)
            suffix = ("?" + parts.query if parts.query else "") + ("#" + parts.fragment if parts.fragment else "")
            return 'href="' + relative + suffix + '"'
        rendered = re.sub(r'href="([^\"]*)"', relocate, rendered)
        write_bounded(output, path.stem + ".html", rendered.encode("utf8"), 100_000_000)
        figures = sum("image/png" in out.get("data", {}) for cell in notebook.cells for out in cell.get("outputs", []))
        if not figures:
            raise RuntimeError(f"笔记本没有嵌入图表：{path.name}")
        records.append({"notebook": path.name, "sha256": sha(path), "figures": figures,
                        "code_cells": sum(cell.cell_type == "code" for cell in notebook.cells)})
        print(f"通过 {path.name}: {figures}幅图", flush=True)
    links = "".join(f'<li><a href="{Path(r["notebook"]).stem}.html">{html.escape(r["notebook"])}</a>（{r["figures"]}幅图）</li>' for r in records)
    index = '<!doctype html><html lang="zh"><meta charset="utf-8"><title>研究Playbook</title><style>body{max-width:960px;margin:50px auto;font:18px/1.8 system-ui;padding:20px}a{color:#176b95}</style><h1>数据与研究 Playbook</h1><p>按00至08阅读。所有图表来自冻结的小型数据或明确标注的教学示例；数据范围与缺口见00。</p><ol>' + links + '</ol></html>'
    write_bounded(output, "index.html", index.encode("utf8"), 100_000_000)
    receipt = {"executed_at": datetime.now(timezone.utc).isoformat(), "python": sys.version,
               "manifest_sha256": sha(data_root() / "manifest.json"), "notebooks": records,
               "helpers": {str(p.relative_to(ROOT)): sha(p) for p in sorted((ROOT / "tools/research_playbook").glob("*.py"))}}
    write_bounded(output, "execution.json", json.dumps(receipt, ensure_ascii=False, indent=2).encode(), 100_000_000)


if __name__ == "__main__":
    main()
