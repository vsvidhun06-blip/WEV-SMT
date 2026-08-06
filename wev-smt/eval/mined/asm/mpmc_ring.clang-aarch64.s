	.text
	.file	"mpmc_ring.c"
	.globl	mpmc_push                       // -- Begin function mpmc_push
	.p2align	2
	.type	mpmc_push,@function
mpmc_push:                              // @mpmc_push
// %bb.0:
	str	x30, [sp, #-64]!                // 8-byte Folded Spill
	stp	x20, x19, [sp, #48]             // 16-byte Folded Spill
	mov	w19, w1
	mov	x20, x0
	stp	x24, x23, [sp, #16]             // 16-byte Folded Spill
	stp	x22, x21, [sp, #32]             // 16-byte Folded Spill
.LBB0_1:                                // =>This Loop Header: Depth=1
                                        //     Child Loop BB0_2 Depth 2
	ldr	w21, [x20]
.LBB0_2:                                //   Parent Loop BB0_1 Depth=1
                                        // =>  This Inner Loop Header: Depth=2
	and	w23, w21, #0x3f
	add	x8, x20, w23, uxtw #3
	add	x24, x8, #8
	ldar	w8, [x24]
	cmp	w8, w21
	b.ne	.LBB0_4
// %bb.3:                               //   in Loop: Header=BB0_2 Depth=2
	add	w22, w21, #1
	mov	w0, w21
	mov	x2, x20
	mov	w1, w22
	bl	__aarch64_cas4_relax
	cmp	w0, w21
	mov	w21, w0
	b.ne	.LBB0_2
	b	.LBB0_6
.LBB0_4:                                //   in Loop: Header=BB0_1 Depth=1
	b.pl	.LBB0_1
// %bb.5:
	mov	w0, wzr
	b	.LBB0_7
.LBB0_6:
	add	x8, x20, x23, lsl #3
	mov	w0, #1                          // =0x1
	str	w19, [x8, #12]
	stlr	w22, [x24]
.LBB0_7:
	ldp	x20, x19, [sp, #48]             // 16-byte Folded Reload
	ldp	x22, x21, [sp, #32]             // 16-byte Folded Reload
	ldp	x24, x23, [sp, #16]             // 16-byte Folded Reload
	ldr	x30, [sp], #64                  // 8-byte Folded Reload
	ret
.Lfunc_end0:
	.size	mpmc_push, .Lfunc_end0-mpmc_push
                                        // -- End function
	.globl	mpmc_pop                        // -- Begin function mpmc_pop
	.p2align	2
	.type	mpmc_pop,@function
mpmc_pop:                               // @mpmc_pop
// %bb.0:
	stp	x30, x23, [sp, #-48]!           // 16-byte Folded Spill
	stp	x20, x19, [sp, #32]             // 16-byte Folded Spill
	mov	x19, x1
	mov	x20, x0
	stp	x22, x21, [sp, #16]             // 16-byte Folded Spill
.LBB1_1:                                // =>This Loop Header: Depth=1
                                        //     Child Loop BB1_2 Depth 2
	ldr	w0, [x20, #4]
.LBB1_2:                                //   Parent Loop BB1_1 Depth=1
                                        // =>  This Inner Loop Header: Depth=2
	and	w23, w0, #0x3f
	add	x8, x20, w23, uxtw #3
	add	x22, x8, #8
	add	w8, w0, #1
	ldar	w1, [x22]
	cmp	w1, w8
	b.ne	.LBB1_4
// %bb.3:                               //   in Loop: Header=BB1_2 Depth=2
	add	x2, x20, #4
	mov	w21, w0
	bl	__aarch64_cas4_relax
	cmp	w0, w21
	b.ne	.LBB1_2
	b	.LBB1_6
.LBB1_4:                                //   in Loop: Header=BB1_1 Depth=1
	b.pl	.LBB1_1
// %bb.5:
	mov	w0, wzr
	b	.LBB1_7
.LBB1_6:
	add	x8, x20, x23, lsl #3
	add	w9, w21, #64
	mov	w0, #1                          // =0x1
	ldr	w8, [x8, #12]
	str	w8, [x19]
	stlr	w9, [x22]
.LBB1_7:
	ldp	x20, x19, [sp, #32]             // 16-byte Folded Reload
	ldp	x22, x21, [sp, #16]             // 16-byte Folded Reload
	ldp	x30, x23, [sp], #48             // 16-byte Folded Reload
	ret
.Lfunc_end1:
	.size	mpmc_pop, .Lfunc_end1-mpmc_pop
                                        // -- End function
	.ident	"Ubuntu clang version 18.1.3 (1ubuntu1)"
	.section	".note.GNU-stack","",@progbits
	.addrsig
