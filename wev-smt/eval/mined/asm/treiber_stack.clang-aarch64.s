	.text
	.file	"treiber_stack.c"
	.globl	treiber_push                    // -- Begin function treiber_push
	.p2align	2
	.type	treiber_push,@function
treiber_push:                           // @treiber_push
// %bb.0:
	stp	x30, x21, [sp, #-32]!           // 16-byte Folded Spill
	stp	x20, x19, [sp, #16]             // 16-byte Folded Spill
	mov	x19, x0
	mov	x20, x1
	ldr	x21, [x0]
.LBB0_1:                                // =>This Inner Loop Header: Depth=1
	mov	x0, x21
	mov	x1, x20
	mov	x2, x19
	str	x21, [x20]
	bl	__aarch64_cas8_rel
	cmp	x0, x21
	mov	x21, x0
	b.ne	.LBB0_1
// %bb.2:
	ldp	x20, x19, [sp, #16]             // 16-byte Folded Reload
	ldp	x30, x21, [sp], #32             // 16-byte Folded Reload
	ret
.Lfunc_end0:
	.size	treiber_push, .Lfunc_end0-treiber_push
                                        // -- End function
	.globl	treiber_pop                     // -- Begin function treiber_pop
	.p2align	2
	.type	treiber_pop,@function
treiber_pop:                            // @treiber_pop
// %bb.0:
	str	x30, [sp, #-32]!                // 8-byte Folded Spill
	stp	x20, x19, [sp, #16]             // 16-byte Folded Spill
	mov	x20, x0
	ldar	x0, [x0]
.LBB1_1:                                // =>This Inner Loop Header: Depth=1
	mov	x19, x0
	cbz	x0, .LBB1_3
// %bb.2:                               //   in Loop: Header=BB1_1 Depth=1
	ldr	x1, [x19]
	mov	x0, x19
	mov	x2, x20
	bl	__aarch64_cas8_acq
	cmp	x0, x19
	b.ne	.LBB1_1
.LBB1_3:
	mov	x0, x19
	ldp	x20, x19, [sp, #16]             // 16-byte Folded Reload
	ldr	x30, [sp], #32                  // 8-byte Folded Reload
	ret
.Lfunc_end1:
	.size	treiber_pop, .Lfunc_end1-treiber_pop
                                        // -- End function
	.globl	treiber_push_tagged             // -- Begin function treiber_push_tagged
	.p2align	2
	.type	treiber_push_tagged,@function
treiber_push_tagged:                    // @treiber_push_tagged
// %bb.0:
	mov	w8, #1                          // =0x1
	ldr	xzr, [x0]
	orr	x8, x8, x1, lsl #16
	stlr	x8, [x0]
	ret
.Lfunc_end2:
	.size	treiber_push_tagged, .Lfunc_end2-treiber_push_tagged
                                        // -- End function
	.ident	"Ubuntu clang version 18.1.3 (1ubuntu1)"
	.section	".note.GNU-stack","",@progbits
	.addrsig
