"""Linux sendmmsg for a bounded burst on an already connected UDP socket."""
import ctypes as C
import os


class Iovec(C.Structure):
    _fields_ = [('base', C.c_void_p), ('length', C.c_size_t)]


class Msg(C.Structure):
    _fields_ = [('name', C.c_void_p), ('namelen', C.c_uint),
                ('iov', C.POINTER(Iovec)), ('iovlen', C.c_size_t),
                ('control', C.c_void_p), ('controllen', C.c_size_t), ('flags', C.c_int)]


class Mmsg(C.Structure):
    _fields_ = [('msg', Msg), ('length', C.c_uint)]


libc = C.CDLL(None, use_errno=True)
libc.sendmmsg.argtypes = [C.c_int, C.POINTER(Mmsg), C.c_uint, C.c_int]
libc.sendmmsg.restype = C.c_int


def send_batch(sock, packets):
    if not packets:
        return
    buffers = [C.create_string_buffer(packet) for packet in packets]
    vectors = (Iovec * len(packets))()
    messages = (Mmsg * len(packets))()
    for i, (buffer, packet) in enumerate(zip(buffers, packets)):
        vectors[i] = Iovec(C.cast(buffer, C.c_void_p), len(packet))
        messages[i].msg.iov = C.pointer(vectors[i])
        messages[i].msg.iovlen = 1
    sent = libc.sendmmsg(sock.fileno(), messages, len(messages), 0)
    if sent < 0:
        error = C.get_errno()
        raise OSError(error, os.strerror(error))
    if sent != len(packets):
        raise RuntimeError(f'partial batch admission: {sent}/{len(packets)}; no retry')
