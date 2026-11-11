import numpy as np, wave
SR=44100; BPM=120; B=60/BPM; DUR=21.0
n=int(SR*DUR); out=np.zeros(n)
def add(sig,t):
    i=int(t*SR); j=min(n,i+len(sig)); out[i:j]+=sig[:j-i]
def env(l,a=0.002,d=0.2):
    t=np.arange(int(l*SR))/SR; return np.minimum(1,t/a)*np.exp(-t/d)
def kick(g=1):
    t=np.arange(int(.35*SR))/SR; f=50+90*np.exp(-t*30); ph=2*np.pi*np.cumsum(f)/SR
    return g*np.sin(ph)*np.exp(-t*7)
def snare(g=.5):
    l=.25; t=np.arange(int(l*SR))/SR; return g*(np.random.randn(len(t))*np.exp(-t*18)+.4*np.sin(2*np.pi*190*t)*np.exp(-t*25))
def hat(g=.12):
    l=.05; t=np.arange(int(l*SR))/SR; x=np.random.randn(len(t)); x=np.diff(np.r_[0,x]); return g*x*np.exp(-t*80)
def tone(f,l,shape='saw',g=.2,d=.3):
    t=np.arange(int(l*SR))/SR
    if shape=='saw': w=2*((f*t)%1)-1
    elif shape=='sq': w=np.sign(np.sin(2*np.pi*f*t))
    else: w=np.sin(2*np.pi*f*t)
    return g*w*env(l,0.005,d)
def lp(x,a): # one-pole low pass
    y=np.zeros_like(x); s=0
    for i in range(len(x)): s+=a*(x[i]-s); y[i]=s
    return y
nt=lambda m:440*2**((m-69)/12)
chords=[(57,[69,72,76]),(53,[65,69,72]),(48,[67,72,76]),(55,[67,71,74])]  # Am F C G
# --- 1999: chiptune, muffled (0-4.0) ---
chip=np.zeros(n)
def addc(sig,t):
    i=int(t*SR); j=min(n,i+len(sig)); chip[i:j]+=sig[:j-i]
for k in range(16):  # 8ths for 4 s at 120 bpm (0.25 s)
    t=k*0.25; root,ch=chords[(k//4)%4]
    addc(tone(nt(ch[k%3]+12),.22,'sq',.08,.12),t)
    if k%2==0: addc(tone(nt(root-12),.24,'sq',.10,.2),t)
chip=lp(chip,0.18)
out+=chip
# coin blip at 0.7
add(np.r_[tone(nt(83),.07,'sq',.18,1),tone(nt(88),.25,'sq',.18,.15)],0.7)
# power off 4.0-4.45: falling tone
t=np.arange(int(.45*SR))/SR; f=900*np.exp(-t*7); add(.22*np.sin(2*np.pi*np.cumsum(f)/SR)*(1-t/.45),4.0)
# riser into drop 4.2-4.55 (noise sweep) and impact at 4.55
t=np.arange(int(.35*SR))/SR; add(.15*np.random.randn(len(t))*(t/.35)**2,4.2)
imp=kick(1.4); add(imp,4.55); t=np.arange(int(1.2*SR))/SR; add(.35*np.sin(2*np.pi*45*t)*np.exp(-t*2.5),4.55)
# --- 2026: full beat from 5.0 to 18.2 ---
start=5.0
k=0
while start+k*B/2<18.2:
    t=start+k*B/2; beat=k//2
    root,ch=chords[(beat//4)%4]
    if k%2==0: add(kick(),t)
    if k%4==2: add(snare(),t)
    add(hat(),t+0.0)
    add(tone(nt(root-12),B/2*.95,'saw',.16,.18),t)
    add(tone(nt(ch[(k)%3]+12),.2,'saw',.07,.08),t)
    add(tone(nt(ch[(k+1)%3]+12),.2,'saw',.05,.08),t+B/4)
    k+=1
# impacts on scene changes
for t in [8.5,11.5,15.0]: add(kick(1.2),t); add(np.random.randn(int(.3*SR))*np.exp(-np.arange(int(.3*SR))/SR*12)*.12,t)
# end: big hit at 18.2, pad chord ringing out
add(kick(1.4),18.2)
for m in [57,64,69,72,76]: add(tone(nt(m),2.8,'saw',.05,1.3),18.2)
out=lp(out,0.55)
out/=np.max(np.abs(out))*1.05
out=np.tanh(out*1.4)/np.tanh(1.4)
st=(np.c_[out,out]*32000).astype('<i2')
w=wave.open('music.wav','wb'); w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes(st.tobytes()); w.close()
print('ok')
