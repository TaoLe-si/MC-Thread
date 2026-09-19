import os, struct, sys, json

def parse(path):
    data = open(path,'rb').read()
    off = 8
    cp = {}
    n = struct.unpack_from('>H', data, off)[0]; off += 2
    i = 1
    while i < n:
        tag = data[off]; off += 1
        if tag == 1:
            ln = struct.unpack_from('>H', data, off)[0]; off += 2
            cp[i] = data[off:off+ln].decode('utf-8','replace'); off += ln
        elif tag in (7,8,16,19,20):
            cp[i] = struct.unpack_from('>H', data, off)[0]; off += 2
        elif tag == 15:
            off += 3
        elif tag in (3,4,9,10,11,12,17,18):
            off += 4
        elif tag in (5,6):
            off += 8; i += 1
        else:
            raise Exception('tag %d' % tag)
        i += 1
    off += 2
    this = cp[cp[struct.unpack_from('>H', data, off)[0]]]; off += 2
    sup = cp[cp[struct.unpack_from('>H', data, off)[0]]]; off += 2
    ni = struct.unpack_from('>H', data, off)[0]; off += 2
    off += 2*ni
    nf = struct.unpack_from('>H', data, off)[0]; off += 2
    for _ in range(nf):
        off += 6
        na = struct.unpack_from('>H', data, off)[0]; off += 2
        for _ in range(na):
            off += 2
            ln = struct.unpack_from('>I', data, off)[0]; off += 4 + ln
    nm = struct.unpack_from('>H', data, off)[0]; off += 2
    methods = []
    for _ in range(nm):
        acc, nidx, didx = struct.unpack_from('>HHH', data, off); off += 6
        name = cp[nidx]; desc = cp[didx]
        na = struct.unpack_from('>H', data, off)[0]; off += 2
        code_len = None
        for _ in range(na):
            an = cp[struct.unpack_from('>H', data, off)[0]]; off += 2
            alen = struct.unpack_from('>I', data, off)[0]; off += 4
            if an == 'Code':
                code_len = struct.unpack_from('>I', data, off+4)[0]
            off += alen
        methods.append((name, desc, code_len))
    return this.replace('/','.'), sup.replace('/','.'), methods

root = sys.argv[1]
out = {}
for dp, dn, fn in os.walk(root):
    for f in fn:
        if f.endswith('.class') and f.startswith('TileEntity') and '$' not in f:
            p = os.path.join(dp, f)
            try:
                this, sup, ms = parse(p)
            except Exception:
                continue
            d = {}
            for m in ms:
                if m[0] == 'onUpdateServer' and m[1] == '()Z' and m[2]:
                    d['onUpdateServer'] = m[2]
            if d:
                out[this] = {'super': sup, 'code': d['onUpdateServer']}
json.dump(out, open(sys.argv[2], 'w'), indent=0)
print(len(out), 'block entities with onUpdateServer')
