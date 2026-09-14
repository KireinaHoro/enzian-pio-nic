#!/usr/bin/env python3
"""Independent arithmetic for pinned fixtures; does not invoke Dandelion/FPGA."""
def mul(a, b):
    return [[sum(x*y for x, y in zip(row, col)) for col in zip(*b)] for row in a]
def gram(a):
    return mul(a, list(map(list, zip(*a))))
def mac(a, b, c):
    return [[x+y for x,y in zip(row, add)] for row,add in zip(mul(a,b),c)]
def wire(a):
    return [len(a)] + [x for row in a for x in row]
a,b,c,d = [[1,1],[1,1]], [[1,2],[3,4]], [[5,6],[7,8]], [[1,-1],[-1,1]]
assert wire(mac(mac(gram(a),gram(b),c),d,gram(b))) == [2,-36,52,-30,66]
assert [wire(gram(mac(x,x,x))) for x in (a,b,c)] == [
    [2,18,18,18,18], [2,208,456,456,1000], [2,12240,16632,16632,22600]]
for value in range(2,6):
    matrix = [[value,0],[0,value]]
    for _ in range(4): matrix = gram(matrix)
    assert wire(matrix) == [2,value**16,0,0,value**16]
print('DAG, sharding and concurrent nested fixture reference results verified')
