	.text
	.file	"ms_queue.c"
	.globl	ms_enqueue                      // -- Begin function ms_enqueue
	.p2align	2
	.type	ms_enqueue,@function
ms_enqueue:                             // @ms_enqueue
// %bb.0:
	stp	x30, x21, [sp, #-32]!           // 16-byte Folded Spill
	stp	x20, x19, [sp, #16]             // 16-byte Folded Spill
	mov	x19, x1
	mov	x20, x0
	str	xzr, [x1]
	b	.LBB0_2
.LBB0_1:                                //   in Loop: Header=BB0_2 Depth=1
	add	x2, x20, #8
	mov	x0, x21
	bl	__aarch64_cas8_rel
.LBB0_2:                                // =>This Inner Loop Header: Depth=1
	add	x8, x20, #8
	ldar	x21, [x8]
	ldar	x1, [x21]
	ldar	x8, [x8]
	cmp	x21, x8
	b.ne	.LBB0_2
// %bb.3:                               //   in Loop: Header=BB0_2 Depth=1
	cbnz	x1, .LBB0_1
// %bb.4:                               //   in Loop: Header=BB0_2 Depth=1
	mov	x0, xzr
	mov	x1, x19
	mov	x2, x21
	bl	__aarch64_cas8_rel
	cmp	x0, #0
	b.ne	.LBB0_2
// %bb.5:
	add	x2, x20, #8
	mov	x0, x21
	mov	x1, x19
	bl	__aarch64_cas8_rel
	ldp	x20, x19, [sp, #16]             // 16-byte Folded Reload
	ldp	x30, x21, [sp], #32             // 16-byte Folded Reload
	ret
.Lfunc_end0:
	.size	ms_enqueue, .Lfunc_end0-ms_enqueue
                                        // -- End function
	.globl	ms_dequeue                      // -- Begin function ms_dequeue
	.p2align	2
	.type	ms_dequeue,@function
ms_dequeue:                             // @ms_dequeue
// %bb.0:
	stp	x30, x21, [sp, #-32]!           // 16-byte Folded Spill
	stp	x20, x19, [sp, #16]             // 16-byte Folded Spill
	mov	x19, x1
	mov	x20, x0
	b	.LBB1_2
.LBB1_1:                                //   in Loop: Header=BB1_2 Depth=1
	add	x2, x20, #8
	bl	__aarch64_cas8_rel
.LBB1_2:                                // =>This Inner Loop Header: Depth=1
	ldar	x21, [x20]
	add	x8, x20, #8
	ldar	x0, [x8]
	ldar	x1, [x21]
	ldar	x8, [x20]
	cmp	x21, x8
	b.ne	.LBB1_2
// %bb.3:                               //   in Loop: Header=BB1_2 Depth=1
	cbz	x1, .LBB1_7
// %bb.4:                               //   in Loop: Header=BB1_2 Depth=1
	cmp	x21, x0
	b.eq	.LBB1_1
// %bb.5:                               //   in Loop: Header=BB1_2 Depth=1
	ldr	w8, [x1, #8]
	mov	x0, x21
	mov	x2, x20
	str	w8, [x19]
	bl	__aarch64_cas8_rel
	cmp	x0, x21
	b.ne	.LBB1_2
// %bb.6:
	mov	w0, #1                          // =0x1
	b	.LBB1_8
.LBB1_7:
	mov	w0, wzr
.LBB1_8:
	ldp	x20, x19, [sp, #16]             // 16-byte Folded Reload
	ldp	x30, x21, [sp], #32             // 16-byte Folded Reload
	ret
.Lfunc_end1:
	.size	ms_dequeue, .Lfunc_end1-ms_dequeue
                                        // -- End function
	.ident	"Ubuntu clang version 18.1.3 (1ubuntu1)"
	.section	".note.GNU-stack","",@progbits
	.addrsig
