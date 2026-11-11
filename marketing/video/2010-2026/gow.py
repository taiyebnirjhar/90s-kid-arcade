import sys, os, time
sys.path.insert(0, "/home/user/kof-arcade/tools")
from pspemu import Psp
ISO = "/tmp/claude-0/-home-user/35fa50b2-b75c-5dae-b675-6700666e2cd4/scratchpad/gos/out/God of War - Ghost of Sparta (USA) (En,Fr,Es).iso"
p = Psp(ISO)
while p.samples == 0:
    time.sleep(0.2); p.run(10)
p.run(5); p.load(open(sys.argv[1], "rb").read()); p.run(120)
os.makedirs("clips/gow", exist_ok=True)
n = [0]
def go(frames, *b, stick=(0,0)):
    for i in range(frames):
        p.run(1, *b, stick=stick)
        if i % 2 == 0:
            p.grab().save(f"clips/gow/{n[0]:05d}.jpg", quality=92); n[0] += 1
seq = [(20,(),(1,0)),(6,("SQUARE",),(0,0)),(10,(),(0,0)),(6,("SQUARE",),(0,0)),(10,(),(0,0)),(6,("TRIANGLE",),(0,0)),(30,(),(0,0)),
       (6,("L","R"),(-1,0)),(20,(),(0,0)),(8,("L",),(0,0)),(8,("L","SQUARE"),(0,0)),(40,(),(0,0)),
       (6,("TRIANGLE",),(0,0)),(10,(),(0,0)),(6,("TRIANGLE",),(0,0)),(10,(),(0,0)),(6,("TRIANGLE",),(0,0)),(30,(),(0,0)),
       (6,("CROSS",),(0,0)),(10,(),(0,0)),(6,("CROSS",),(0,0)),(8,(),(0,0)),(6,("SQUARE",),(0,0)),(40,(),(0,0)),
       (60,("SQUARE",),(0,0)),(40,(),(0,0))]
for f,b,s in seq: go(f,*b,stick=s)
print("gow", n[0]); os._exit(0)
