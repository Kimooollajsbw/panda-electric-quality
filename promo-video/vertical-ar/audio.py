import numpy as np, wave
sr=44100; T=17.0; n=int(sr*T); t=np.arange(n)/sr; out=np.zeros(n)
bpm=100; beat=60/bpm; step=beat/4
hz=lambda m:440*2**((m-69)/12)
rng=np.random.default_rng(7)
def add(start,sig):
    i=int(start*sr); j=min(n,i+len(sig)); 
    if i<n: out[i:j]+=sig[:j-i]
def tone(f,dur,decay,amp,saw=False):
    x=np.arange(int(dur*sr))/sr
    s=np.sin(2*np.pi*f*x) if not saw else sum(np.sin(2*np.pi*f*k*x)/k for k in range(1,7))
    return amp*s*np.exp(-x*decay)*np.clip(x/0.004,0,1)
# progression Dm Bb F C (one bar each)
prog=[[62,65,69],[58,62,65],[53,57,60],[60,64,67]]
bass=[38,34,41,36]
bar=4*beat; nb=int(T/bar)+1
for b in range(nb):
    s0=b*bar; ch=prog[b%4]
    # pad (soft saw, filtered by low harmonics) whole time
    for m in ch: add(s0,tone(hz(m)/2,bar+.3,0.6,0.025,saw=True))
    # pluck arp 16ths from 1.2s
    arp=[ch[0],ch[1],ch[2],ch[1]+12,ch[2],ch[0]+12,ch[1],ch[2]+12]
    for k in range(16):
        st=s0+k*step
        if st>=1.2 and st<16.2: add(st,tone(hz(arp[k%8]+12),.25,22,0.07))
    if s0>=3.0 and s0<16:
        for k in (0,1.5,2.5):  # syncopated sub bass
            add(s0+k*beat,tone(hz(bass[b%4]),beat*.9,3,0.28))
# drums from 3.2s
bt=0.0
while bt<16.3:
    if bt>=3.2:
        x=np.arange(int(.35*sr))/sr
        add(bt,0.55*np.sin(2*np.pi*(45+110*np.exp(-x*35))*x)*np.exp(-x*8))
        k=int(round(bt/beat))
        if k%2==1:
            x=np.arange(int(.2*sr))/sr; nz=rng.standard_normal(len(x))
            add(bt,0.18*nz*(np.exp(-x*30)+0.5*np.exp(-np.maximum(x-.012,0)*30)*(x>.012)))
        for h in (0.5,):
            x=np.arange(int(.05*sr))/sr; add(bt+h*beat,0.05*rng.standard_normal(len(x))*np.exp(-x*90))
    bt+=beat
# risers / whooshes and impacts
for c in (3.2,6.5,9.1):
    d=.6; x=np.arange(int(d*sr))/sr; nz=np.convolve(rng.standard_normal(len(x)),np.ones(6)/6,'same')
    add(c-.4,0.3*nz*np.sin(np.pi*x/d)**2)
for c in (1.35,3.6,4.5,8.65,14.7):
    x=np.arange(int(.12*sr))/sr; add(c,0.22*np.sin(2*np.pi*(1200-4000*x)*x)*np.exp(-x*40))
x=np.arange(int(1.5*sr))/sr; add(14.65,0.4*np.sin(2*np.pi*(40+60*np.exp(-x*10))*x)*np.exp(-x*3))
out*=np.clip((T-t)/0.9,0,1)*np.clip(t/0.2,0,1)
out=np.tanh(out*1.4); out=out/np.max(np.abs(out))*0.89
w=wave.open('music.wav','wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr); w.writeframes((out*32767).astype(np.int16).tobytes()); w.close()
