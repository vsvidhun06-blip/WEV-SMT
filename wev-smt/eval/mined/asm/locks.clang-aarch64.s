	.text
	.file	"locks.c"
	.globl	dekker_p0                       // -- Begin function dekker_p0
	.p2align	2
	.type	dekker_p0,@function
dekker_p0:                              // @dekker_p0
// %bb.0:
	mov	x8, x0
	mov	w0, #1                          // =0x1
	str	w0, [x8]
	ldr	w9, [x8, #4]
	cbz	w9, .LBB0_2
// %bb.1:
	mov	w0, wzr
	ret
.LBB0_2:
	str	w0, [x8, #12]
	ret
.Lfunc_end0:
	.size	dekker_p0, .Lfunc_end0-dekker_p0
                                        // -- End function
	.globl	peterson_p0                     // -- Begin function peterson_p0
	.p2align	2
	.type	peterson_p0,@function
peterson_p0:                            // @peterson_p0
// %bb.0:
	mov	w9, #1                          // =0x1
	add	x8, x0, #4
	add	x10, x0, #8
	str	w9, [x0]
	str	wzr, [x0, #8]
	ldar	w8, [x8]
	ldar	w10, [x10]
	cbz	w8, .LBB1_3
// %bb.1:
	mov	w8, wzr
	cbnz	w10, .LBB1_3
// %bb.2:
	mov	w0, w8
	ret
.LBB1_3:
	mov	w8, #1                          // =0x1
	str	w9, [x0, #12]
	mov	w0, w8
	ret
.Lfunc_end1:
	.size	peterson_p0, .Lfunc_end1-peterson_p0
                                        // -- End function
	.globl	ticket_lock                     // -- Begin function ticket_lock
	.p2align	2
	.type	ticket_lock,@function
ticket_lock:                            // @ticket_lock
// %bb.0:
	stp	x30, x19, [sp, #-16]!           // 16-byte Folded Spill
	mov	x19, x0
	mov	w0, #1                          // =0x1
	mov	x1, x19
	bl	__aarch64_ldadd4_relax
.LBB2_1:                                // =>This Inner Loop Header: Depth=1
	add	x8, x19, #4
	ldar	w8, [x8]
	cmp	w8, w0
	b.ne	.LBB2_1
// %bb.2:
	ldp	x30, x19, [sp], #16             // 16-byte Folded Reload
	ret
.Lfunc_end2:
	.size	ticket_lock, .Lfunc_end2-ticket_lock
                                        // -- End function
	.globl	ticket_unlock                   // -- Begin function ticket_unlock
	.p2align	2
	.type	ticket_unlock,@function
ticket_unlock:                          // @ticket_unlock
// %bb.0:
	ldr	w8, [x0, #4]
	add	x9, x0, #4
	add	w8, w8, #1
	stlr	w8, [x9]
	ret
.Lfunc_end3:
	.size	ticket_unlock, .Lfunc_end3-ticket_unlock
                                        // -- End function
	.globl	ticket_waiters                  // -- Begin function ticket_waiters
	.p2align	2
	.type	ticket_waiters,@function
ticket_waiters:                         // @ticket_waiters
// %bb.0:
	ldr	w8, [x0]
	ldr	w9, [x0, #4]
	sub	w0, w8, w9
	ret
.Lfunc_end4:
	.size	ticket_waiters, .Lfunc_end4-ticket_waiters
                                        // -- End function
	.globl	mcs_acquire                     // -- Begin function mcs_acquire
	.p2align	2
	.type	mcs_acquire,@function
mcs_acquire:                            // @mcs_acquire
// %bb.0:
	stp	x30, x19, [sp, #-16]!           // 16-byte Folded Spill
	mov	x19, x1
	mov	x1, x0
	mov	x0, x19
	str	xzr, [x19]
	bl	__aarch64_swp8_acq_rel
	cbz	x0, .LBB5_3
// %bb.1:
	mov	w8, #1                          // =0x1
	str	w8, [x19, #8]
	stlr	x19, [x0]
.LBB5_2:                                // =>This Inner Loop Header: Depth=1
	add	x8, x19, #8
	ldar	w8, [x8]
	cbnz	w8, .LBB5_2
.LBB5_3:
	ldp	x30, x19, [sp], #16             // 16-byte Folded Reload
	ret
.Lfunc_end5:
	.size	mcs_acquire, .Lfunc_end5-mcs_acquire
                                        // -- End function
	.globl	mcs_release                     // -- Begin function mcs_release
	.p2align	2
	.type	mcs_release,@function
mcs_release:                            // @mcs_release
// %bb.0:
	ldar	x8, [x1]
	cbz	x8, .LBB6_2
// %bb.1:
	add	x8, x8, #8
	stlr	wzr, [x8]
.LBB6_2:
	ret
.Lfunc_end6:
	.size	mcs_release, .Lfunc_end6-mcs_release
                                        // -- End function
	.ident	"Ubuntu clang version 18.1.3 (1ubuntu1)"
	.section	".note.GNU-stack","",@progbits
	.addrsig
