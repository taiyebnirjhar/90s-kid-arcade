import sys, os, json
sys.path.insert(0, "/home/user/kof-arcade/tools")
from emu import Emu
game, name = sys.argv[1], sys.argv[2]
e = Emu(game)
out = f"clips/{name}"; os.makedirs(out, exist_ok=True)
log = []; n = [0]; rec = [False]; f=[0]
DIRS = {1:["DOWN","LEFT"],2:["DOWN"],3:["DOWN","RIGHT"],4:["LEFT"],5:[],6:["RIGHT"],7:["UP","LEFT"],8:["UP"],9:["UP","RIGHT"]}
def step(d=5, btns=(), move=None, frames=1):
    for _ in range(frames):
        e.held[0] = set(DIRS[d]) | set(btns); e.lib.retro_run(); f[0]+=1
        if f[0] % 2 == 0 and rec[0]:
            e.frame.save(f"{out}/{n[0]:05d}.jpg", quality=92); log.append(dict(dir=d, btns=list(btns), move=move)); n[0] += 1
    e.held[0] = set()
step(frames=int(sys.argv[3]))
step(5,["COIN"],None,6); step(frames=60); step(5,["START"],None,6); step(frames=120)
if name=="dinoplay":
    step(frames=60)
    for r in range(int(os.environ.get("RIGHTS","2"))): step(6,(),None,5); step(frames=12)
for i in range(int(sys.argv[4])): step(5,["A"],None,4); step(frames=20)
rec[0] = True
if name == "mslugplay":
    for k in range(14):
        for j in range(6): step(6,["A"],None,4); step(6,(),None,4)
        step(6,["B"],None,6); step(6,(),None,14)
        if k%3==2: step(5,["C"],None,5); step(frames=10)
        step(2,["A"],None,4); step(2,(),None,4)
elif name == "dinoplay":
    for k in range(14):
        step(6,(),None,24)
        for j in range(4): step(5,["A"],None,4); step(5,(),None,8)
        step(6,["B"],None,6); step(6,(),None,8); step(3,["A"],None,5); step(5,(),None,16)
if name == "sg3play":
    for k in range(14):
        step(6,(),None,20)
        for j in range(3): step(5,["A"],None,4); step(5,(),None,6)
        step(6,["B"],None,4); step(6,(),None,10)
        step(5,["C"],None,5); step(5,(),None,10)
        step(5,["A","B"],None,5); step(5,(),None,14)
open(f"{out}.json","w").write(json.dumps(log)); print(name, n[0]); os._exit(0)
