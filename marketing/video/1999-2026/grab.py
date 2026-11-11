import sys, os
sys.path.insert(0, "/home/user/kof-arcade/tools")
from emu import Emu
game, name, a, b = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
os.makedirs(f"clips/{name}", exist_ok=True)
e = Emu(game)
e.run(a)
i = 0
for f in range(a, b, 2):
    e.run(2)
    e.frame.save(f"clips/{name}/{i:05d}.jpg", quality=92); i += 1
print(name, i)
os._exit(0)
