00000000000065b0 <rpc_handle_call>:
    65b0:	d10783ff 	sub	sp, sp, #0x1e0
    65b4:	d2802402 	mov	x2, #0x120                 	// #288
    65b8:	a91a7bfd 	stp	x29, x30, [sp, #416]
    65bc:	910683fd 	add	x29, sp, #0x1a0
    65c0:	a91c5bf5 	stp	x21, x22, [sp, #448]
    65c4:	b00000d6 	adrp	x22, 1f000 <__FRAME_END__+0x1334c>
    65c8:	aa0103f5 	mov	x21, x1
    65cc:	a91b53f3 	stp	x19, x20, [sp, #432]
    65d0:	aa0003f3 	mov	x19, x0
    65d4:	9102e3f4 	add	x20, sp, #0xb8
    65d8:	f947eac0 	ldr	x0, [x22, #4048]
    65dc:	a91d63f7 	stp	x23, x24, [sp, #464]
    65e0:	f94006b8 	ldr	x24, [x21, #8]
    65e4:	f9400001 	ldr	x1, [x0]
    65e8:	f900cfe1 	str	x1, [sp, #408]
    65ec:	d2800001 	mov	x1, #0x0                   	// #0
    65f0:	f9400260 	ldr	x0, [x19]
    65f4:	39407000 	ldrb	w0, [x0, #28]
    65f8:	39050260 	strb	w0, [x19, #320]
    65fc:	9101e3e0 	add	x0, sp, #0x78
    6600:	97fff440 	bl	3700 <memset@plt>
    6604:	3dc002bd 	ldr	q29, [x21]
    6608:	3dc006be 	ldr	q30, [x21, #16]
    660c:	3dc00abf 	ldr	q31, [x21, #32]
    6610:	f9400b00 	ldr	x0, [x24, #16]
    6614:	3dc01a7a 	ldr	q26, [x19, #96]
    6618:	3dc01e7b 	ldr	q27, [x19, #112]
    661c:	3dc0227c 	ldr	q28, [x19, #128]
    6620:	91004000 	add	x0, x0, #0x10
    6624:	3c8783fd 	stur	q29, [sp, #120]
    6628:	3c8883fe 	stur	q30, [sp, #136]
    662c:	3dc0267d 	ldr	q29, [x19, #144]
    6630:	3dc02a7e 	ldr	q30, [x19, #160]
    6634:	3c8983ff 	stur	q31, [sp, #152]
    6638:	3dc02e7f 	ldr	q31, [x19, #176]
    663c:	3d80069b 	str	q27, [x20, #16]
    6640:	3d800a9c 	str	q28, [x20, #32]
    6644:	3dc0367b 	ldr	q27, [x19, #208]
    6648:	3dc03a7c 	ldr	q28, [x19, #224]
    664c:	3c8b83fa 	stur	q26, [sp, #184]
    6650:	3d800e9d 	str	q29, [x20, #48]
    6654:	3dc0327a 	ldr	q26, [x19, #192]
    6658:	3dc03e7d 	ldr	q29, [x19, #240]
    665c:	3d80129e 	str	q30, [x20, #64]
    6660:	3d80169f 	str	q31, [x20, #80]
    6664:	3dc0427e 	ldr	q30, [x19, #256]
    6668:	3dc0467f 	ldr	q31, [x19, #272]
    666c:	3d801e9b 	str	q27, [x20, #112]
    6670:	3d80229c 	str	q28, [x20, #128]
    6674:	3d801a9a 	str	q26, [x20, #96]
    6678:	3d80269d 	str	q29, [x20, #144]
    667c:	f90057e0 	str	x0, [sp, #168]
    6680:	3d802e9f 	str	q31, [x20, #176]
    6684:	3d802a9e 	str	q30, [x20, #160]
    6688:	3dc04a7e 	ldr	q30, [x19, #288]
    668c:	b94012a0 	ldr	w0, [x21, #16]
    6690:	3dc04e7f 	ldr	q31, [x19, #304]
    6694:	b900ebe0 	str	w0, [sp, #232]
    6698:	f94016a0 	ldr	x0, [x21, #40]
    669c:	3d80329e 	str	q30, [x20, #192]
    66a0:	3d80369f 	str	q31, [x20, #208]
    66a4:	b900efe0 	str	w0, [sp, #236]
    66a8:	aa1403e0 	mov	x0, x20
    66ac:	97fff459 	bl	3810 <server_trace_stamp@plt>
