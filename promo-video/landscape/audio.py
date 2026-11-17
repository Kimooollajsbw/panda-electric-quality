import numpy as np, wave
sr=44100; T=17.0; n=int(sr*T); t=np.arange(n)/sr; out=np.zeros(n)
beat=60/86.0
def env(x,a,r): return np.clip(x/a,0,1)*np.exp(-np.maximum(x,0)/r)
# pad: Am F C G, 2 bars each
chords=[[57,60,64],[53,57,60],[48,55,60],[55,59,62]]
hz=lambda m:440*2**((m-69)/12)
bar=4*beat
for i in range(int(T/(2*bar))+1):
    s=i*2*bar; m=(t>=s)&(t<s+2*bar+.5); x=t[m]-s
    e=np.clip(x/0.6,0,1)*np.clip((2*bar+.5-x)/0.5,0,1)
    for note in chords[i%4]:
        f=hz(note)
        out[m]+=0.06*e*(np.sin(2*np.pi*f*x)+0.3*np.sin(2*np.pi*f*2.003*x)+0.15*np.sin(2*np.pi*f*0.5*x))
# kick from 3.3s, hats
k=0; tt=0.0
while tt<T-0.3:
    if tt>=3.2:
        m=(t>=tt)&(t<tt+.4); x=t[m]-tt
        out[m]+=0.5*np.sin(2*np.pi*(50+90*np.exp(-x*30))*x)*np.exp(-x*9)
    if tt>=3.2 and tt<16.0:
        for off in (beat/2,):
            h=tt+off; m=(t>=h)&(t<h+.06); x=t[m]-h
            out[m]+=0.05*np.random.randn(m.sum())*np.exp(-x*70)
    tt+=beat
# whooshes (noise swell) at transitions and pops
rng=np.random.default_rng(1)
for c in (3.2,6.5,9.1):
    m=(t>=c-.35)&(t<c+.25); x=t[m]-(c-.35)
    nz=rng.standard_normal(m.sum()); nz=np.convolve(nz,np.ones(8)/8,'same')
    out[m]+=0.35*nz*np.sin(np.pi*x/.6)**2
for c in (1.35,3.6,4.5,8.65,14.7):
    m=(t>=c)&(t<c+.12); x=t[m]-c
    out[m]+=0.25*np.sin(2*np.pi*(900-3000*x)*x)*np.exp(-x*40)
fade=np.clip((T-t)/0.8,0,1)*np.clip(t/0.3,0,1); out*=fade
out=out/np.max(np.abs(out))*0.85
d=(out*32767).astype(np.int16)
w=wave.open('music.wav','wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr); w.writeframes(d.tobytes()); w.close()
