0000000000009008 <server_trace_stage>:
    9008:	aa0003e2 	mov	x2, x0
    900c:	b40003e0 	cbz	x0, 9088 <server_trace_stage+0x80>
    9010:	39408000 	ldrb	w0, [x0, #32]
    9014:	360003a0 	tbz	w0, #0, 9088 <server_trace_stage+0x80>
    9018:	b9405440 	ldr	w0, [x2, #84]
    901c:	34000360 	cbz	w0, 9088 <server_trace_stage+0x80>
    9020:	7100081f 	cmp	w0, #0x2
    9024:	540000c0 	b.eq	903c <server_trace_stage+0x34>  // b.none
    9028:	7100283f 	cmp	w1, #0xa
    902c:	540002e8 	b.hi	9088 <server_trace_stage+0x80>  // b.pmore
    9030:	d28080a0 	mov	x0, #0x405                 	// #1029
    9034:	9ac12400 	lsr	x0, x0, x1
    9038:	36000280 	tbz	w0, #0, 9088 <server_trace_stage+0x80>
    903c:	a9be7bfd 	stp	x29, x30, [sp, #-32]!
    9040:	d37b7c20 	ubfiz	x0, x1, #5, #32
    9044:	910003fd 	mov	x29, sp
    9048:	91018000 	add	x0, x0, #0x60
    904c:	8b000040 	add	x0, x2, x0
    9050:	f9000be2 	str	x2, [sp, #16]
    9054:	b9001fe1 	str	w1, [sp, #28]
    9058:	97ffeb7a 	bl	3e40 <lh_pmu_read@plt>
    905c:	f9400be2 	ldr	x2, [sp, #16]
    9060:	52800020 	mov	w0, #0x1                   	// #1
    9064:	b9401fe1 	ldr	w1, [sp, #28]
    9068:	1ac12001 	lsl	w1, w0, w1
    906c:	b9405840 	ldr	w0, [x2, #88]
    9070:	2a010000 	orr	w0, w0, w1
    9074:	b9005840 	str	w0, [x2, #88]
    9078:	aa0203e0 	mov	x0, x2
    907c:	a8c27bfd 	ldp	x29, x30, [sp], #32
    9080:	17ffeb38 	b	3d60 <server_trace_stamp@plt>
    9084:	d503201f 	nop
    9088:	aa0203e0 	mov	x0, x2
    908c:	17ffeb35 	b	3d60 <server_trace_stamp@plt>
