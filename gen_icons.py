import struct, zlib

def make_png(size):
    def chunk(t, d):
        c = t + d
        return struct.pack('>I', len(d)) + c + struct.pack('>I', zlib.crc32(c) & 0xffffffff)
    
    sig = b'\x89PNG\r\n\x1a\n'
    ihdr = chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 2, 0, 0, 0))
    raw = b''
    for y in range(size):
        raw += b'\x00'
        for x in range(size):
            r = min(255, 10 + int((x/size)*15))
            g = min(255, 10 + int((x/size)*15))
            b = min(255, 16 + int((x/size)*15))
            raw += struct.pack('BBBB', r, g, b, 255)
    idat = chunk(b'IDAT', zlib.compress(raw))
    iend = chunk(b'IEND', b'')
    return sig + ihdr + idat + iend

with open('public/icon-192.png', 'wb') as f:
    f.write(make_png(192))
with open('public/icon-512.png', 'wb') as f:
    f.write(make_png(512))
print('Icons created')
