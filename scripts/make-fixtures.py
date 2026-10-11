"""Create local, synthetic performance fixtures without touching app data."""
from pathlib import Path
root = Path(__file__).resolve().parents[1] / "work" / "performance"
root.mkdir(parents=True, exist_ok=True)
line = "const value = '日本語テキスト'; // performance fixture\n".encode()
for size in (1, 5, 20):
    target = size * 1024 * 1024
    data = line * (target // len(line))
    (root / f"{size}MiB.js").write_bytes(data + b" " * (target - len(data)))
(root / "1500rows.csv").write_text("name,value\n" + "\n".join(f"row{i},{i}" for i in range(1500)), encoding="utf-8")
print(f"Created synthetic fixtures in {root}")
