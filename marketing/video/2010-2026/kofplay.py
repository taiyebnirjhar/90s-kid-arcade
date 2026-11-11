import sys, os, json
sys.path.insert(0, "/home/user/kof-arcade/tools")
from emu import Emu
e = Emu("kf2k2mp2")
e.run(5); e.load(open("kof_fight.state","rb").read())
out = "clips/kofplay"; os.makedirs(out, exist_ok=True)
log = []; n = [0]; f = [0]
DIRS = {1:["DOWN","LEFT"],2:["DOWN"],3:["DOWN","RIGHT"],4:["LEFT"],5:[],6:["RIGHT"],7:["UP","LEFT"],8:["UP"],9:["UP","RIGHT"]}
def step(d=5, btns=(), move=None, frames=1):
    for _ in range(frames):
        e.held[0] = set(DIRS[d]) | set(btns); e.lib.retro_run(); f[0] += 1
        if f[0] % 2 == 0 and rec[0]:
            e.frame.save(f"{out}/{n[0]:05d}.jpg", quality=92)
            log.append(dict(dir=d, btns=list(btns), move=move)); n[0] += 1
    e.held[0] = set()
def motion(seq, btn, move, hold=4):
    for c in seq: step(int(c), (), move, 2)
    step(int(seq[-1]), btn, move, hold)
rec = [False]
step(frames=230)  # to the fight
rec[0] = True
def walk(fr): step(6, (), None, fr)
for loop in range(3):
    walk(24)
    motion("236", ["A"], 3); step(frames=40)            # Aragami (fireball)
    walk(20)
    step(2, ["B"], None, 5); step(2, (), None, 6); step(2, ["B"], None, 5); step(frames=4)
    motion("236", ["A"], 3); step(frames=30)
    step(9, (), None, 8); step(5, (), None, 10); step(5, ["C"], None, 5); step(frames=16); step(5, ["C"], None, 5)
    motion("623", ["A"], 5); step(frames=40)              # Oniyaki
    walk(16)
    step(5, ["B","C"], 'sys0', 6); step(frames=30)        # MAX
    motion("2141236", ["A"], 1); step(frames=90)           # Orochinagi super
open(f"{out}.json","w").write(json.dumps(log))
print("frames", n[0]); os._exit(0)
