0000000000007248 <consume_outcome>:
    7248:	a9bb7bfd 	stp	x29, x30, [sp, #-80]!
    724c:	910003fd 	mov	x29, sp
    7250:	a90153f3 	stp	x19, x20, [sp, #16]
    7254:	a9025bf5 	stp	x21, x22, [sp, #32]
    7258:	a90363f7 	stp	x23, x24, [sp, #48]
    725c:	a9046bf9 	stp	x25, x26, [sp, #64]
    7260:	d10bc3ff 	sub	sp, sp, #0x2f0
    7264:	900000d7 	adrp	x23, 1f000 <__FRAME_END__+0x1051c>
    7268:	aa0003f4 	mov	x20, x0
    726c:	aa0103f3 	mov	x19, x1
    7270:	f947eae0 	ldr	x0, [x23, #4048]
    7274:	12001c55 	and	w21, w2, #0xff
    7278:	f9400678 	ldr	x24, [x19, #8]
    727c:	f9400a7a 	ldr	x26, [x19, #16]
    7280:	f9400001 	ldr	x1, [x0]
    7284:	f90177e1 	str	x1, [sp, #744]
    7288:	d2800001 	mov	x1, #0x0                   	// #0
    728c:	f9401679 	ldr	x25, [x19, #40]
    7290:	97fff30c 	bl	3ec0 <worker_get_current@plt>
    7294:	b9400261 	ldr	w1, [x19]
    7298:	7100083f 	cmp	w1, #0x2
    729c:	54000760 	b.eq	7388 <consume_outcome+0x140>  // b.none
    72a0:	540004c8 	b.hi	7338 <consume_outcome+0xf0>  // b.pmore
    72a4:	34000861 	cbz	w1, 73b0 <consume_outcome+0x168>
    72a8:	b9400660 	ldr	w0, [x19, #4]
    72ac:	91010283 	add	x3, x20, #0x40
    72b0:	d2800002 	mov	x2, #0x0                   	// #0
    72b4:	51000c01 	sub	w1, w0, #0x3
    72b8:	71000c3f 	cmp	w1, #0x3
    72bc:	528000a1 	mov	w1, #0x5                   	// #5
    72c0:	1a813001 	csel	w1, w0, w1, cc	// cc = lo, ul, last
    72c4:	aa1403e0 	mov	x0, x20
    72c8:	97fffdc0 	bl	69c8 <send_reply>
    72cc:	f9400e61 	ldr	x1, [x19, #24]
    72d0:	b4000061 	cbz	x1, 72dc <consume_outcome+0x94>
    72d4:	aa1803e0 	mov	x0, x24
    72d8:	d63f0020 	blr	x1
    72dc:	52800015 	mov	w21, #0x0                   	// #0
    72e0:	f947eae0 	ldr	x0, [x23, #4048]
    72e4:	f94177e2 	ldr	x2, [sp, #744]
    72e8:	f9400001 	ldr	x1, [x0]
    72ec:	eb010042 	subs	x2, x2, x1
    72f0:	d2800001 	mov	x1, #0x0                   	// #0
    72f4:	54001d41 	b.ne	769c <consume_outcome+0x454>  // b.any
    72f8:	910bc3ff 	add	sp, sp, #0x2f0
    72fc:	2a1503e0 	mov	w0, w21
    7300:	a94153f3 	ldp	x19, x20, [sp, #16]
    7304:	a9425bf5 	ldp	x21, x22, [sp, #32]
    7308:	a94363f7 	ldp	x23, x24, [sp, #48]
    730c:	a9446bf9 	ldp	x25, x26, [sp, #64]
    7310:	a8c57bfd 	ldp	x29, x30, [sp], #80
    7314:	d2800001 	mov	x1, #0x0                   	// #0
    7318:	d2800002 	mov	x2, #0x0                   	// #0
    731c:	d2800003 	mov	x3, #0x0                   	// #0
    7320:	d2800004 	mov	x4, #0x0                   	// #0
    7324:	d2800005 	mov	x5, #0x0                   	// #0
    7328:	d2800006 	mov	x6, #0x0                   	// #0
    732c:	d2800010 	mov	x16, #0x0                   	// #0
    7330:	d2800011 	mov	x17, #0x0                   	// #0
    7334:	d65f03c0 	ret
    7338:	71000c3f 	cmp	w1, #0x3
    733c:	540002e1 	b.ne	7398 <consume_outcome+0x150>  // b.any
    7340:	b4001419 	cbz	x25, 75c0 <consume_outcome+0x378>
    7344:	f9400f20 	ldr	x0, [x25, #24]
    7348:	b40013c0 	cbz	x0, 75c0 <consume_outcome+0x378>
    734c:	f9400001 	ldr	x1, [x0]
    7350:	b4001381 	cbz	x1, 75c0 <consume_outcome+0x378>
    7354:	f9401a82 	ldr	x2, [x20, #48]
    7358:	91010295 	add	x21, x20, #0x40
    735c:	f9400423 	ldr	x3, [x1, #8]
    7360:	f9400044 	ldr	x4, [x2]
    7364:	f9400481 	ldr	x1, [x4, #8]
    7368:	eb01007f 	cmp	x3, x1
    736c:	540014a0 	b.eq	7600 <consume_outcome+0x3b8>  // b.none
    7370:	aa1503e3 	mov	x3, x21
    7374:	d2800002 	mov	x2, #0x0                   	// #0
    7378:	528000a1 	mov	w1, #0x5                   	// #5
    737c:	aa1403e0 	mov	x0, x20
    7380:	97fffd92 	bl	69c8 <send_reply>
    7384:	17ffffd2 	b	72cc <consume_outcome+0x84>
    7388:	37000b55 	tbnz	w21, #0, 74f0 <consume_outcome+0x2a8>
    738c:	d2800001 	mov	x1, #0x0                   	// #0
    7390:	aa0103e0 	mov	x0, x1
    7394:	97fff2b7 	bl	3e70 <free@plt>
    7398:	91010283 	add	x3, x20, #0x40
    739c:	d2800002 	mov	x2, #0x0                   	// #0
    73a0:	528000a1 	mov	w1, #0x5                   	// #5
    73a4:	aa1403e0 	mov	x0, x20
    73a8:	97fffd88 	bl	69c8 <send_reply>
    73ac:	17ffffc8 	b	72cc <consume_outcome+0x84>
    73b0:	91010295 	add	x21, x20, #0x40
    73b4:	528000e1 	mov	w1, #0x7                   	// #7
    73b8:	aa0003f6 	mov	x22, x0
    73bc:	aa1503e0 	mov	x0, x21
    73c0:	97fff194 	bl	3a10 <server_trace_stage@plt>
    73c4:	f9012280 	str	x0, [x20, #576]
    73c8:	f9401a80 	ldr	x0, [x20, #48]
    73cc:	f9400001 	ldr	x1, [x0]
    73d0:	f9401823 	ldr	x3, [x1, #48]
    73d4:	b4001063 	cbz	x3, 75e0 <consume_outcome+0x398>
    73d8:	f9400402 	ldr	x2, [x0, #8]
    73dc:	aa1a03e1 	mov	x1, x26
    73e0:	aa1803e0 	mov	x0, x24
    73e4:	d63f0060 	blr	x3
    73e8:	aa0003e3 	mov	x3, x0
    73ec:	f116b01f 	cmp	x0, #0x5ac
    73f0:	54000ec8 	b.hi	75c8 <consume_outcome+0x380>  // b.pmore
    73f4:	394102c0 	ldrb	w0, [x22, #64]
    73f8:	910082c5 	add	x5, x22, #0x20
    73fc:	37000f60 	tbnz	w0, #0, 75e8 <consume_outcome+0x3a0>
    7400:	f94020a0 	ldr	x0, [x5, #64]
    7404:	f101007f 	cmp	x3, #0x40
    7408:	f9404ec1 	ldr	x1, [x22, #152]
    740c:	1a9f87e4 	cset	w4, ls	// ls = plast
    7410:	39400002 	ldrb	w2, [x0]
    7414:	52826000 	mov	w0, #0x1300                	// #4864
    7418:	f90014a3 	str	x3, [x5, #40]
    741c:	f90018a1 	str	x1, [x5, #48]
    7420:	53196042 	lsl	w2, w2, #7
    7424:	0b000042 	add	w2, w2, w0
    7428:	52800020 	mov	w0, #0x1                   	// #1
    742c:	390080a0 	strb	w0, [x5, #32]
    7430:	8b224022 	add	x2, x1, w2, uxtw
    7434:	f9401ca0 	ldr	x0, [x5, #56]
    7438:	91000400 	add	x0, x0, #0x1
    743c:	f9001ca0 	str	x0, [x5, #56]
    7440:	39400840 	ldrb	w0, [x2, #2]
    7444:	33196080 	bfi	w0, w4, #7, #25
    7448:	39000840 	strb	w0, [x2, #2]
    744c:	d5033fbf 	dmb	sy
    7450:	d2828004 	mov	x4, #0x1400                	// #5120
    7454:	91010042 	add	x2, x2, #0x40
    7458:	8b040021 	add	x1, x1, x4
    745c:	9a828021 	csel	x1, x1, x2, hi	// hi = pmore
    7460:	f90036c1 	str	x1, [x22, #104]
    7464:	b4000c41 	cbz	x1, 75ec <consume_outcome+0x3a4>
    7468:	f9401a80 	ldr	x0, [x20, #48]
    746c:	f90003e3 	str	x3, [sp]
    7470:	f90007e5 	str	x5, [sp, #8]
    7474:	f9400002 	ldr	x2, [x0]
    7478:	f9400404 	ldr	x4, [x0, #8]
    747c:	aa1803e0 	mov	x0, x24
    7480:	f9400446 	ldr	x6, [x2, #8]
    7484:	aa1a03e2 	mov	x2, x26
    7488:	d63f00c0 	blr	x6
    748c:	2a0003f6 	mov	w22, w0
    7490:	52800101 	mov	w1, #0x8                   	// #8
    7494:	aa1503e0 	mov	x0, x21
    7498:	97fff15e 	bl	3a10 <server_trace_stage@plt>
    749c:	f94003e3 	ldr	x3, [sp]
    74a0:	93407ec2 	sxtw	x2, w22
    74a4:	710002df 	cmp	w22, #0x0
    74a8:	f9012680 	str	x0, [x20, #584]
    74ac:	fa43a042 	ccmp	x2, x3, #0x2, ge	// ge = tcont
    74b0:	54000ec9 	b.ls	7688 <consume_outcome+0x440>  // b.plast
    74b4:	f94007e5 	ldr	x5, [sp, #8]
    74b8:	394080a0 	ldrb	w0, [x5, #32]
    74bc:	3607f5a0 	tbz	w0, #0, 7370 <consume_outcome+0x128>
    74c0:	4f00041f 	movi	v31.4s, #0x0
    74c4:	528000e0 	mov	w0, #0x7                   	// #7
    74c8:	b9001be0 	str	w0, [sp, #24]
    74cc:	910063e2 	add	x2, sp, #0x18
    74d0:	f94018a0 	ldr	x0, [x5, #48]
    74d4:	aa0503e1 	mov	x1, x5
    74d8:	3c82c3ff 	stur	q31, [sp, #44]
    74dc:	3c81c3ff 	stur	q31, [sp, #28]
    74e0:	3c8383ff 	stur	q31, [sp, #56]
    74e4:	97fffc51 	bl	6628 <core_eci_tx_commit>
    74e8:	17ffffa2 	b	7370 <consume_outcome+0x128>
    74ec:	d503201f 	nop
    74f0:	f9401260 	ldr	x0, [x19, #32]
    74f4:	b4fff4c0 	cbz	x0, 738c <consume_outcome+0x144>
    74f8:	d2806000 	mov	x0, #0x300                 	// #768
    74fc:	97fff1a1 	bl	3b80 <malloc@plt>
    7500:	aa0003f6 	mov	x22, x0
    7504:	b4fff440 	cbz	x0, 738c <consume_outcome+0x144>
    7508:	f90003e0 	str	x0, [sp]
    750c:	97fff2c5 	bl	4020 <fiber_sched_acquire@plt>
    7510:	f94003e1 	ldr	x1, [sp]
    7514:	aa0003e4 	mov	x4, x0
    7518:	b4fff3c0 	cbz	x0, 7390 <consume_outcome+0x148>
    751c:	b9420280 	ldr	w0, [x20, #512]
    7520:	f90003e4 	str	x4, [sp]
    7524:	32000000 	orr	w0, w0, #0x1
    7528:	b9020280 	str	w0, [x20, #512]
    752c:	91010280 	add	x0, x20, #0x40
    7530:	97fff20c 	bl	3d60 <server_trace_stamp@plt>
    7534:	aa1403e1 	mov	x1, x20
    7538:	d2805402 	mov	x2, #0x2a0                 	// #672
    753c:	f9013280 	str	x0, [x20, #608]
    7540:	f9014a9f 	str	xzr, [x20, #656]
    7544:	910123e0 	add	x0, sp, #0x48
    7548:	aa0003f4 	mov	x20, x0
    754c:	97fff111 	bl	3990 <memcpy@plt>
    7550:	d2806002 	mov	x2, #0x300                 	// #768
    7554:	52800001 	mov	w1, #0x0                   	// #0
    7558:	aa1603e0 	mov	x0, x22
    755c:	97fff1b9 	bl	3c40 <memset@plt>
    7560:	aa1403e1 	mov	x1, x20
    7564:	d2805402 	mov	x2, #0x2a0                 	// #672
    7568:	aa1603e0 	mov	x0, x22
    756c:	97fff109 	bl	3990 <memcpy@plt>
    7570:	3dc0027d 	ldr	q29, [x19]
    7574:	90000002 	adrp	x2, 7000 <submit_call+0x1d0>
    7578:	3dc0067e 	ldr	q30, [x19, #16]
    757c:	f0ffffe1 	adrp	x1, 6000 <pending_call_table_has_expired+0x10>
    7580:	3dc00a7f 	ldr	q31, [x19, #32]
    7584:	aa1603e3 	mov	x3, x22
    7588:	f94003e0 	ldr	x0, [sp]
    758c:	91238042 	add	x2, x2, #0x8e0
    7590:	3d80aadd 	str	q29, [x22, #672]
    7594:	9123e021 	add	x1, x1, #0x8f8
    7598:	3d80aede 	str	q30, [x22, #688]
    759c:	910102d6 	add	x22, x22, #0x40
    75a0:	3d80a2df 	str	q31, [x22, #640]
    75a4:	97fff1fb 	bl	3d90 <fiber_prepare@plt>
    75a8:	f94003e4 	ldr	x4, [sp]
    75ac:	aa0403e0 	mov	x0, x4
    75b0:	f9000096 	str	x22, [x4]
    75b4:	97fff23f 	bl	3eb0 <fiber_sched_submit@plt>
    75b8:	17ffff4a 	b	72e0 <consume_outcome+0x98>
    75bc:	d503201f 	nop
    75c0:	91010295 	add	x21, x20, #0x40
    75c4:	17ffff6b 	b	7370 <consume_outcome+0x128>
    75c8:	52800101 	mov	w1, #0x8                   	// #8
    75cc:	aa1503e0 	mov	x0, x21
    75d0:	97fff110 	bl	3a10 <server_trace_stage@plt>
    75d4:	f9012680 	str	x0, [x20, #584]
    75d8:	17ffff66 	b	7370 <consume_outcome+0x128>
    75dc:	d503201f 	nop
    75e0:	d280b583 	mov	x3, #0x5ac                 	// #1452
    75e4:	17ffff84 	b	73f4 <consume_outcome+0x1ac>
    75e8:	f90036df 	str	xzr, [x22, #104]
    75ec:	b0000020 	adrp	x0, c000 <bitmap_acquire+0x8>
    75f0:	91130000 	add	x0, x0, #0x4c0
    75f4:	97fff1ef 	bl	3db0 <puts@plt>
    75f8:	17ffff35 	b	72cc <consume_outcome+0x84>
    75fc:	d503201f 	nop
    7600:	f9400401 	ldr	x1, [x0, #8]
    7604:	f9400440 	ldr	x0, [x2, #8]
    7608:	eb00003f 	cmp	x1, x0
    760c:	54ffeb21 	b.ne	7370 <consume_outcome+0x128>  // b.any
    7610:	910063e3 	add	x3, sp, #0x18
    7614:	aa1a03e2 	mov	x2, x26
    7618:	aa1803e1 	mov	x1, x24
    761c:	aa1903e0 	mov	x0, x25
    7620:	97fffe7a 	bl	7008 <prepare_call>
    7624:	aa0003f6 	mov	x22, x0
    7628:	b4ffea40 	cbz	x0, 7370 <consume_outcome+0x128>
    762c:	3dc0029d 	ldr	q29, [x20]
    7630:	52800020 	mov	w0, #0x1                   	// #1
    7634:	3dc0069e 	ldr	q30, [x20, #16]
    7638:	3dc00a9f 	ldr	q31, [x20, #32]
    763c:	b9001ec0 	str	w0, [x22, #28]
    7640:	b9420280 	ldr	w0, [x20, #512]
    7644:	3d800add 	str	q29, [x22, #32]
    7648:	3d800ede 	str	q30, [x22, #48]
    764c:	3d8012df 	str	q31, [x22, #64]
    7650:	321f0000 	orr	w0, w0, #0x2
    7654:	b9020280 	str	w0, [x20, #512]
    7658:	aa1503e0 	mov	x0, x21
    765c:	97fff1c1 	bl	3d60 <server_trace_stamp@plt>
    7660:	aa1503e1 	mov	x1, x21
    7664:	d2804c02 	mov	x2, #0x260                 	// #608
    7668:	f9013680 	str	x0, [x20, #616]
    766c:	910142c0 	add	x0, x22, #0x50
    7670:	97fff0c8 	bl	3990 <memcpy@plt>
    7674:	b9401be1 	ldr	w1, [sp, #24]
    7678:	aa1603e0 	mov	x0, x22
    767c:	97fffded 	bl	6e30 <submit_call>
    7680:	34ffe260 	cbz	w0, 72cc <consume_outcome+0x84>
    7684:	17ffff3b 	b	7370 <consume_outcome+0x128>
    7688:	aa1503e3 	mov	x3, x21
    768c:	52800001 	mov	w1, #0x0                   	// #0
    7690:	aa1403e0 	mov	x0, x20
    7694:	97fffccd 	bl	69c8 <send_reply>
    7698:	17ffff0d 	b	72cc <consume_outcome+0x84>
    769c:	97fff18d 	bl	3cd0 <__stack_chk_fail@plt>
