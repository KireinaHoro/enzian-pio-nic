0000000000009568 <server_trace_event>:
    9568:	90000464 	adrp	x4, 95000 <pending_call_table+0x74360>
    956c:	aa0003e5 	mov	x5, x0
    9570:	91040084 	add	x4, x4, #0x100
    9574:	f9403480 	ldr	x0, [x4, #104]
    9578:	f100001f 	cmp	x0, #0x0
    957c:	fa4018a4 	ccmp	x5, #0x0, #0x4, ne	// ne = any
    9580:	54000940 	b.eq	96a8 <server_trace_event+0x140>  // b.none
    9584:	394080a0 	ldrb	w0, [x5, #32]
    9588:	36000900 	tbz	w0, #0, 96a8 <server_trace_event+0x140>
    958c:	a9ba7bfd 	stp	x29, x30, [sp, #-96]!
    9590:	910003fd 	mov	x29, sp
    9594:	d00000a0 	adrp	x0, 1f000 <__FRAME_END__+0x1051c>
    9598:	f9478c06 	ldr	x6, [x0, #3864]
    959c:	913c6000 	add	x0, x0, #0xf18
    95a0:	d63f00c0 	blr	x6
    95a4:	d53bd046 	mrs	x6, tpidr_el0
    95a8:	f86068c6 	ldr	x6, [x6, x0]
    95ac:	b4000a66 	cbz	x6, 96f8 <server_trace_event+0x190>
    95b0:	f9403c84 	ldr	x4, [x4, #120]
    95b4:	f9401cc0 	ldr	x0, [x6, #56]
    95b8:	eb04001f 	cmp	x0, x4
    95bc:	54000920 	b.eq	96e0 <server_trace_event+0x178>  // b.none
    95c0:	f94018c9 	ldr	x9, [x6, #48]
    95c4:	d37ff804 	lsl	x4, x0, #1
    95c8:	8b000084 	add	x4, x4, x0
    95cc:	aa0503e0 	mov	x0, x5
    95d0:	d37cec84 	lsl	x4, x4, #4
    95d4:	8b040127 	add	x7, x9, x4
    95d8:	f90017e7 	str	x7, [sp, #40]
    95dc:	f9001be9 	str	x9, [sp, #48]
    95e0:	f9001fe4 	str	x4, [sp, #56]
    95e4:	f90023e6 	str	x6, [sp, #64]
    95e8:	b9004fe1 	str	w1, [sp, #76]
    95ec:	f9002be2 	str	x2, [sp, #80]
    95f0:	f9002fe3 	str	x3, [sp, #88]
    95f4:	f9000be5 	str	x5, [sp, #16]
    95f8:	97ffe9da 	bl	3d60 <server_trace_stamp@plt>
    95fc:	f9400be5 	ldr	x5, [sp, #16]
    9600:	f90013e0 	str	x0, [sp, #32]
    9604:	f94014aa 	ldr	x10, [x5, #40]
    9608:	b94030a5 	ldr	w5, [x5, #48]
    960c:	f9000fea 	str	x10, [sp, #24]
    9610:	b90013e5 	str	w5, [sp, #16]
    9614:	97ffe93f 	bl	3b10 <sched_getcpu@plt>
    9618:	f94013e8 	ldr	x8, [sp, #32]
    961c:	f9401be9 	ldr	x9, [sp, #48]
    9620:	f9401fe4 	ldr	x4, [sp, #56]
    9624:	f94017e7 	ldr	x7, [sp, #40]
    9628:	b94013e5 	ldr	w5, [sp, #16]
    962c:	f8246928 	str	x8, [x9, x4]
    9630:	f9400fea 	ldr	x10, [sp, #24]
    9634:	f9402be2 	ldr	x2, [sp, #80]
    9638:	f9402fe3 	ldr	x3, [sp, #88]
    963c:	290400e5 	stp	w5, w0, [x7, #32]
    9640:	f94023e6 	ldr	x6, [sp, #64]
    9644:	b9404fe1 	ldr	w1, [sp, #76]
    9648:	f9401cc0 	ldr	x0, [x6, #56]
    964c:	f90004ea 	str	x10, [x7, #8]
    9650:	f90008e2 	str	x2, [x7, #16]
    9654:	91000400 	add	x0, x0, #0x1
    9658:	f9000ce3 	str	x3, [x7, #24]
    965c:	b90028e1 	str	w1, [x7, #40]
    9660:	f9001cc0 	str	x0, [x6, #56]
    9664:	910120c6 	add	x6, x6, #0x48
    9668:	c89ffcc0 	stlr	x0, [x6]
    966c:	a8c67bfd 	ldp	x29, x30, [sp], #96
    9670:	d2800000 	mov	x0, #0x0                   	// #0
    9674:	d2800001 	mov	x1, #0x0                   	// #0
    9678:	d2800002 	mov	x2, #0x0                   	// #0
    967c:	d2800003 	mov	x3, #0x0                   	// #0
    9680:	d2800004 	mov	x4, #0x0                   	// #0
    9684:	d2800005 	mov	x5, #0x0                   	// #0
    9688:	d2800006 	mov	x6, #0x0                   	// #0
    968c:	d2800007 	mov	x7, #0x0                   	// #0
    9690:	d2800008 	mov	x8, #0x0                   	// #0
    9694:	d2800009 	mov	x9, #0x0                   	// #0
    9698:	d280000a 	mov	x10, #0x0                   	// #0
    969c:	d2800010 	mov	x16, #0x0                   	// #0
    96a0:	d2800011 	mov	x17, #0x0                   	// #0
    96a4:	d65f03c0 	ret
    96a8:	d2800000 	mov	x0, #0x0                   	// #0
    96ac:	d2800001 	mov	x1, #0x0                   	// #0
    96b0:	d2800002 	mov	x2, #0x0                   	// #0
    96b4:	d2800003 	mov	x3, #0x0                   	// #0
    96b8:	d2800004 	mov	x4, #0x0                   	// #0
    96bc:	d2800005 	mov	x5, #0x0                   	// #0
    96c0:	d2800006 	mov	x6, #0x0                   	// #0
    96c4:	d2800007 	mov	x7, #0x0                   	// #0
    96c8:	d2800008 	mov	x8, #0x0                   	// #0
    96cc:	d2800009 	mov	x9, #0x0                   	// #0
    96d0:	d280000a 	mov	x10, #0x0                   	// #0
    96d4:	d2800010 	mov	x16, #0x0                   	// #0
    96d8:	d2800011 	mov	x17, #0x0                   	// #0
    96dc:	d65f03c0 	ret
    96e0:	f94020c0 	ldr	x0, [x6, #64]
    96e4:	91000400 	add	x0, x0, #0x1
    96e8:	f90020c0 	str	x0, [x6, #64]
    96ec:	910140c6 	add	x6, x6, #0x50
    96f0:	c89ffcc0 	stlr	x0, [x6]
    96f4:	17ffffde 	b	966c <server_trace_event+0x104>
    96f8:	91010084 	add	x4, x4, #0x40
    96fc:	d2800020 	mov	x0, #0x1                   	// #1
    9700:	f8200080 	ldadd	x0, x0, [x4]
    9704:	17ffffda 	b	966c <server_trace_event+0x104>
