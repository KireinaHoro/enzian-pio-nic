00000000000076a0 <rpc_handle_call>:
    76a0:	a9bc7bfd 	stp	x29, x30, [sp, #-64]!
    76a4:	910003fd 	mov	x29, sp
    76a8:	d2805402 	mov	x2, #0x2a0                 	// #672
    76ac:	a90153f3 	stp	x19, x20, [sp, #16]
    76b0:	a9025bf5 	stp	x21, x22, [sp, #32]
    76b4:	a90363f7 	stp	x23, x24, [sp, #48]
    76b8:	d10c83ff 	sub	sp, sp, #0x320
    76bc:	900000d6 	adrp	x22, 1f000 <__FRAME_END__+0x1051c>
    76c0:	aa0003f5 	mov	x21, x0
    76c4:	aa0103f3 	mov	x19, x1
    76c8:	f947eac0 	ldr	x0, [x22, #4048]
    76cc:	9102e3f4 	add	x20, sp, #0xb8
    76d0:	f9400678 	ldr	x24, [x19, #8]
    76d4:	f9400001 	ldr	x1, [x0]
    76d8:	f9018fe1 	str	x1, [sp, #792]
    76dc:	d2800001 	mov	x1, #0x0                   	// #0
    76e0:	f94002a0 	ldr	x0, [x21]
    76e4:	39407000 	ldrb	w0, [x0, #28]
    76e8:	390c22a0 	strb	w0, [x21, #776]
    76ec:	9101e3e0 	add	x0, sp, #0x78
    76f0:	97fff154 	bl	3c40 <memset@plt>
    76f4:	f9400b00 	ldr	x0, [x24, #16]
    76f8:	d2804c02 	mov	x2, #0x260                 	// #608
    76fc:	3dc0027d 	ldr	q29, [x19]
    7700:	9102a2a1 	add	x1, x21, #0xa8
    7704:	3dc0067e 	ldr	q30, [x19, #16]
    7708:	3dc00a7f 	ldr	q31, [x19, #32]
    770c:	91004000 	add	x0, x0, #0x10
    7710:	f90057e0 	str	x0, [sp, #168]
    7714:	3c8783fd 	stur	q29, [sp, #120]
    7718:	aa1403e0 	mov	x0, x20
    771c:	3c8883fe 	stur	q30, [sp, #136]
    7720:	3c8983ff 	stur	q31, [sp, #152]
    7724:	97fff09b 	bl	3990 <memcpy@plt>
    7728:	b9401260 	ldr	w0, [x19, #16]
    772c:	52800061 	mov	w1, #0x3                   	// #3
    7730:	b900ebe0 	str	w0, [sp, #232]
    7734:	f9401660 	ldr	x0, [x19, #40]
    7738:	b900efe0 	str	w0, [sp, #236]
    773c:	aa1403e0 	mov	x0, x20
    7740:	97fff0b4 	bl	3a10 <server_trace_stage@plt>
    7744:	f9401661 	ldr	x1, [x19, #40]
    7748:	52800023 	mov	w3, #0x1                   	// #1
    774c:	f94057e2 	ldr	x2, [sp, #168]
    7750:	f9014fe0 	str	x0, [sp, #664]
    7754:	f94016a0 	ldr	x0, [x21, #40]
    7758:	97fffb74 	bl	6528 <decode>
    775c:	f9005be0 	str	x0, [sp, #176]
    7760:	52800081 	mov	w1, #0x4                   	// #4
    7764:	aa1403e0 	mov	x0, x20
    7768:	97fff0aa 	bl	3a10 <server_trace_stage@plt>
    776c:	f90153e0 	str	x0, [sp, #672]
    7770:	f9405be0 	ldr	x0, [sp, #176]
    7774:	b40009e0 	cbz	x0, 78b0 <rpc_handle_call+0x210>
    7778:	900000c0 	adrp	x0, 1f000 <__FRAME_END__+0x1051c>
    777c:	f947ac01 	ldr	x1, [x0, #3928]
    7780:	913d6000 	add	x0, x0, #0xf58
    7784:	d63f0020 	blr	x1
    7788:	d53bd041 	mrs	x1, tpidr_el0
    778c:	f8606825 	ldr	x5, [x1, x0]
    7790:	8b000024 	add	x4, x1, x0
    7794:	f8206834 	str	x20, [x1, x0]
    7798:	528000a1 	mov	w1, #0x5                   	// #5
    779c:	aa1403e0 	mov	x0, x20
    77a0:	f90003e5 	str	x5, [sp]
    77a4:	f90007e4 	str	x4, [sp, #8]
    77a8:	97fff09a 	bl	3a10 <server_trace_stage@plt>
    77ac:	f9400703 	ldr	x3, [x24, #8]
    77b0:	910123e8 	add	x8, sp, #0x48
    77b4:	b9401262 	ldr	w2, [x19, #16]
    77b8:	f9405be1 	ldr	x1, [sp, #176]
    77bc:	f90157e0 	str	x0, [sp, #680]
    77c0:	f9017be0 	str	x0, [sp, #752]
    77c4:	f9400300 	ldr	x0, [x24]
    77c8:	d63f0060 	blr	x3
    77cc:	b9404be2 	ldr	w2, [sp, #72]
    77d0:	d2800003 	mov	x3, #0x0                   	// #0
    77d4:	52800021 	mov	w1, #0x1                   	// #1
    77d8:	aa1403e0 	mov	x0, x20
    77dc:	97fff11d 	bl	3c50 <server_trace_event@plt>
    77e0:	528000c1 	mov	w1, #0x6                   	// #6
    77e4:	aa1403e0 	mov	x0, x20
    77e8:	97fff08a 	bl	3a10 <server_trace_stage@plt>
    77ec:	f94157e1 	ldr	x1, [sp, #680]
    77f0:	52800022 	mov	w2, #0x1                   	// #1
    77f4:	3cc483fd 	ldur	q29, [sp, #72]
    77f8:	f94003e5 	ldr	x5, [sp]
    77fc:	f94007e4 	ldr	x4, [sp, #8]
    7800:	3cc583fe 	ldur	q30, [sp, #88]
    7804:	3cc683ff 	ldur	q31, [sp, #104]
    7808:	f9000085 	str	x5, [x4]
    780c:	f9015be0 	str	x0, [sp, #688]
    7810:	f9017fe0 	str	x0, [sp, #760]
    7814:	3d8007fd 	str	q29, [sp, #16]
    7818:	cb010000 	sub	x0, x0, x1
    781c:	f90187e0 	str	x0, [sp, #776]
    7820:	910043e1 	add	x1, sp, #0x10
    7824:	9101e3e0 	add	x0, sp, #0x78
    7828:	3d80043e 	str	q30, [x1, #16]
    782c:	3d80083f 	str	q31, [x1, #32]
    7830:	97fffe86 	bl	7248 <consume_outcome>
    7834:	360002e0 	tbz	w0, #0, 7890 <rpc_handle_call+0x1f0>
    7838:	f947ead6 	ldr	x22, [x22, #4048]
    783c:	f9418fe0 	ldr	x0, [sp, #792]
    7840:	f94002c1 	ldr	x1, [x22]
    7844:	eb010000 	subs	x0, x0, x1
    7848:	d2800001 	mov	x1, #0x0                   	// #0
    784c:	54000461 	b.ne	78d8 <rpc_handle_call+0x238>  // b.any
    7850:	910c83ff 	add	sp, sp, #0x320
    7854:	a94153f3 	ldp	x19, x20, [sp, #16]
    7858:	a9425bf5 	ldp	x21, x22, [sp, #32]
    785c:	a94363f7 	ldp	x23, x24, [sp, #48]
    7860:	a8c47bfd 	ldp	x29, x30, [sp], #64
    7864:	d2800000 	mov	x0, #0x0                   	// #0
    7868:	d2800001 	mov	x1, #0x0                   	// #0
    786c:	d2800002 	mov	x2, #0x0                   	// #0
    7870:	d2800003 	mov	x3, #0x0                   	// #0
    7874:	d2800004 	mov	x4, #0x0                   	// #0
    7878:	d2800005 	mov	x5, #0x0                   	// #0
    787c:	d2800008 	mov	x8, #0x0                   	// #0
    7880:	d2800010 	mov	x16, #0x0                   	// #0
    7884:	d2800011 	mov	x17, #0x0                   	// #0
    7888:	d65f03c0 	ret
    788c:	d503201f 	nop
    7890:	f94057e0 	ldr	x0, [sp, #168]
    7894:	f9400001 	ldr	x1, [x0]
    7898:	f9400402 	ldr	x2, [x0, #8]
    789c:	f9405be0 	ldr	x0, [sp, #176]
    78a0:	f9401023 	ldr	x3, [x1, #32]
    78a4:	52800001 	mov	w1, #0x0                   	// #0
    78a8:	d63f0060 	blr	x3
    78ac:	17ffffe3 	b	7838 <rpc_handle_call+0x198>
    78b0:	97fff1f4 	bl	4080 <__errno_location@plt>
    78b4:	b9400000 	ldr	w0, [x0]
    78b8:	aa1403e3 	mov	x3, x20
    78bc:	d2800002 	mov	x2, #0x0                   	// #0
    78c0:	7100301f 	cmp	w0, #0xc
    78c4:	aa1303e0 	mov	x0, x19
    78c8:	1a9f17e1 	cset	w1, eq	// eq = none
    78cc:	11001021 	add	w1, w1, #0x4
    78d0:	97fffc3e 	bl	69c8 <send_reply>
    78d4:	17ffffd9 	b	7838 <rpc_handle_call+0x198>
    78d8:	97fff0fe 	bl	3cd0 <__stack_chk_fail@plt>
    78dc:	d503201f 	nop
