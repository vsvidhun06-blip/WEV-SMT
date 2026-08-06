	.text
	.file	"dclp_barrier.c"
	.globl	dclp_get                        // -- Begin function dclp_get
	.p2align	2
	.type	dclp_get,@function
dclp_get:                               // @dclp_get
// %bb.0:
	str	x30, [sp, #-32]!                // 8-byte Folded Spill
	stp	x20, x19, [sp, #16]             // 16-byte Folded Spill
	mov	x19, x0
	ldar	x0, [x0]
	cbnz	x0, .LBB0_6
// %bb.1:
	mov	x20, x1
.LBB0_2:                                // =>This Inner Loop Header: Depth=1
	add	x1, x19, #8
	mov	w0, #1                          // =0x1
	bl	__aarch64_swp4_acq
	cbnz	w0, .LBB0_2
// %bb.3:
	ldr	x0, [x19]
	cbnz	x0, .LBB0_5
// %bb.4:
	mov	x0, x20
	stlr	x20, [x19]
.LBB0_5:
	add	x8, x19, #8
	stlr	wzr, [x8]
.LBB0_6:
	ldp	x20, x19, [sp, #16]             // 16-byte Folded Reload
	ldr	x30, [sp], #32                  // 8-byte Folded Reload
	ret
.Lfunc_end0:
	.size	dclp_get, .Lfunc_end0-dclp_get
                                        // -- End function
	.globl	barrier_wait                    // -- Begin function barrier_wait
	.p2align	2
	.type	barrier_wait,@function
barrier_wait:                           // @barrier_wait
// %bb.0:
	stp	x30, x21, [sp, #-32]!           // 16-byte Folded Spill
	ldr	w8, [x2]
	stp	x20, x19, [sp, #16]             // 16-byte Folded Spill
	mov	x20, x0
	mov	w21, w1
	mov	w0, #1                          // =0x1
	mov	x1, x20
	eor	w8, w8, #0x1
	mov	x19, x2
	str	w8, [x2]
	bl	__aarch64_ldadd4_acq_rel
	add	w8, w0, #1
	cmp	w8, w21
	b.ne	.LBB1_2
// %bb.1:
	str	wzr, [x20]
	add	x9, x20, #4
	ldr	w8, [x19]
	stlr	w8, [x9]
	b	.LBB1_3
.LBB1_2:                                // =>This Inner Loop Header: Depth=1
	add	x8, x20, #4
	ldar	w8, [x8]
	ldr	w9, [x19]
	cmp	w8, w9
	b.ne	.LBB1_2
.LBB1_3:
	ldp	x20, x19, [sp, #16]             // 16-byte Folded Reload
	ldp	x30, x21, [sp], #32             // 16-byte Folded Reload
	ret
.Lfunc_end1:
	.size	barrier_wait, .Lfunc_end1-barrier_wait
                                        // -- End function
	.globl	rc_acquire                      // -- Begin function rc_acquire
	.p2align	2
	.type	rc_acquire,@function
rc_acquire:                             // @rc_acquire
// %bb.0:
	str	x30, [sp, #-16]!                // 8-byte Folded Spill
	mov	x1, x0
	mov	w0, #1                          // =0x1
	bl	__aarch64_ldadd4_relax
	ldr	x30, [sp], #16                  // 8-byte Folded Reload
	ret
.Lfunc_end2:
	.size	rc_acquire, .Lfunc_end2-rc_acquire
                                        // -- End function
	.globl	rc_release                      // -- Begin function rc_release
	.p2align	2
	.type	rc_release,@function
rc_release:                             // @rc_release
// %bb.0:
	str	x30, [sp, #-16]!                // 8-byte Folded Spill
	mov	x1, x0
	mov	w0, #-1                         // =0xffffffff
	bl	__aarch64_ldadd4_rel
	cmp	w0, #1
	b.ne	.LBB3_2
// %bb.1:
	dmb	ishld
	ldr	x30, [sp], #16                  // 8-byte Folded Reload
	ret
.LBB3_2:
	mov	w0, wzr
	ldr	x30, [sp], #16                  // 8-byte Folded Reload
	ret
.Lfunc_end3:
	.size	rc_release, .Lfunc_end3-rc_release
                                        // -- End function
	.globl	rc_rebalance                    // -- Begin function rc_rebalance
	.p2align	2
	.type	rc_rebalance,@function
rc_rebalance:                           // @rc_rebalance
// %bb.0:
	mov	w8, #1                          // =0x1
	ldar	wzr, [x1]
	str	w8, [x0, #4]
	ret
.Lfunc_end4:
	.size	rc_rebalance, .Lfunc_end4-rc_rebalance
                                        // -- End function
	.ident	"Ubuntu clang version 18.1.3 (1ubuntu1)"
	.section	".note.GNU-stack","",@progbits
	.addrsig
