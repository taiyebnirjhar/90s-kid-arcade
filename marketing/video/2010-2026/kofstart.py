import sys, os
sys.path.insert(0, "/home/user/kof-arcade/tools")
from emu import Emu
e = Emu("kf2k2mp2")
e.run(1200); e.tap("COIN", 6, 60); e.tap("START", 6, 180)
e.shot("mk/sel0")
for i in range(40):
    e.tap("A", 4, 20)
    if i % 8 == 0: e.shot(f"mk/sel{i}")
e.run(300); e.shot("mk/after")
open("kof_fight.state","wb").write(e.save())
os._exit(0)
