	.text
	.file	"rwlock_bitops.c"
	.globl	rw_read_lock                    // -- Begin function rw_read_lock
	.p2align	2
	.type	rw_read_lock,@function
rw_read_lock:                           // @rw_read_lock
// %bb.0:
	str	x30, [sp, #-32]!                // 8-byte Folded Spill
	stp	x20, x19, [sp, #16]             // 16-byte Folded Spill
	mov	x19, x0
.LBB0_1:                                // =>This Inner Loop Header: Depth=1
	ldar	w20, [x19]
	tbnz	w20, #0, .LBB0_1
// %bb.2:                               //   in Loop: Header=BB0_1 Depth=1
	add	w1, w20, #2
	mov	w0, w20
	mov	x2, x19
	bl	__aarch64_cas4_acq
	cmp	w0, w20
	b.ne	.LBB0_1
// %bb.3:
	ldp	x20, x19, [sp, #16]             // 16-byte Folded Reload
	ldr	x30, [sp], #32                  // 8-byte Folded Reload
	ret
.Lfunc_end0:
	.size	rw_read_lock, .Lfunc_end0-rw_read_lock
                                        // -- End function
	.globl	rw_read_unlock                  // -- Begin function rw_read_unlock
	.p2align	2
	.type	rw_read_unlock,@function
rw_read_unlock:                         // @rw_read_unlock
// %bb.0:
	str	x30, [sp, #-16]!                // 8-byte Folded Spill
	mov	x1, x0
	mov	w0, #-2                         // =0xfffffffe
	bl	__aarch64_ldadd4_rel
	ldr	x30, [sp], #16                  // 8-byte Folded Reload
	ret
.Lfunc_end1:
	.size	rw_read_unlock, .Lfunc_end1-rw_read_unlock
                                        // -- End function
	.globl	rw_lowest_bit                   // -- Begin function rw_lowest_bit
	.p2align	2
	.type	rw_lowest_bit,@function
rw_lowest_bit:                          // @rw_lowest_bit
// %bb.0:
	ldar	w8, [x0]
	neg	w9, w8
	and	w8, w8, w9
	str	w8, [x1]
	ret
.Lfunc_end2:
	.size	rw_lowest_bit, .Lfunc_end2-rw_lowest_bit
                                        // -- End function
	.globl	rw_clear_lowest                 // -- Begin function rw_clear_lowest
	.p2align	2
	.type	rw_clear_lowest,@function
rw_clear_lowest:                        // @rw_clear_lowest
// %bb.0:
	ldar	w8, [x0]
	sub	w9, w8, #1
	and	w8, w9, w8
	str	w8, [x1]
	ret
.Lfunc_end3:
	.size	rw_clear_lowest, .Lfunc_end3-rw_clear_lowest
                                        // -- End function
	.globl	rw_mask_degenerate              // -- Begin function rw_mask_degenerate
	.p2align	2
	.type	rw_mask_degenerate,@function
rw_mask_degenerate:                     // @rw_mask_degenerate
// %bb.0:
	mov	w8, #1                          // =0x1
	ldar	wzr, [x0]
	str	w8, [x1]
	ret
.Lfunc_end4:
	.size	rw_mask_degenerate, .Lfunc_end4-rw_mask_degenerate
                                        // -- End function
	.globl	rw_or_degenerate                // -- Begin function rw_or_degenerate
	.p2align	2
	.type	rw_or_degenerate,@function
rw_or_degenerate:                       // @rw_or_degenerate
// %bb.0:
	mov	w8, #1                          // =0x1
	ldar	wzr, [x0]
	str	w8, [x1]
	ret
.Lfunc_end5:
	.size	rw_or_degenerate, .Lfunc_end5-rw_or_degenerate
                                        // -- End function
	.globl	rw_roundtrip                    // -- Begin function rw_roundtrip
	.p2align	2
	.type	rw_roundtrip,@function
rw_roundtrip:                           // @rw_roundtrip
// %bb.0:
	ldar	w8, [x0]
	str	w8, [x1]
	ret
.Lfunc_end6:
	.size	rw_roundtrip, .Lfunc_end6-rw_roundtrip
                                        // -- End function
	.ident	"Ubuntu clang version 18.1.3 (1ubuntu1)"
	.section	".note.GNU-stack","",@progbits
	.addrsig
