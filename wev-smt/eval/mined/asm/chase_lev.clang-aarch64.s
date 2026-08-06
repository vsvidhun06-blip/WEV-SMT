	.text
	.file	"chase_lev.c"
	.globl	cl_push                         // -- Begin function cl_push
	.p2align	2
	.type	cl_push,@function
cl_push:                                // @cl_push
// %bb.0:
	ldr	x8, [x0, #8]
	ldar	x9, [x0]
	sub	x9, x8, x9
	cmp	x9, #1023
	b.gt	.LBB0_2
// %bb.1:
	and	x9, x8, #0x3ff
	add	x8, x8, #1
	add	x9, x0, x9, lsl #2
	str	w1, [x9, #16]
	dmb	ish
	str	x8, [x0, #8]
.LBB0_2:
	ret
.Lfunc_end0:
	.size	cl_push, .Lfunc_end0-cl_push
                                        // -- End function
	.globl	cl_steal                        // -- Begin function cl_steal
	.p2align	2
	.type	cl_steal,@function
cl_steal:                               // @cl_steal
// %bb.0:
	stp	x30, x21, [sp, #-32]!           // 16-byte Folded Spill
	stp	x20, x19, [sp, #16]             // 16-byte Folded Spill
	add	x8, x0, #8
	ldar	x19, [x0]
	dmb	ish
	ldar	x8, [x8]
	cmp	x19, x8
	b.ge	.LBB1_3
// %bb.1:
	and	x8, x19, #0x3ff
	mov	x2, x0
	mov	x20, x1
	add	x8, x0, x8, lsl #2
	add	x1, x19, #1
	mov	x0, x19
	ldr	w21, [x8, #16]
	bl	__aarch64_cas8_acq_rel
	cmp	x0, x19
	b.ne	.LBB1_3
// %bb.2:
	mov	w0, #1                          // =0x1
	str	w21, [x20]
	b	.LBB1_4
.LBB1_3:
	mov	w0, wzr
.LBB1_4:
	ldp	x20, x19, [sp, #16]             // 16-byte Folded Reload
	ldp	x30, x21, [sp], #32             // 16-byte Folded Reload
	ret
.Lfunc_end1:
	.size	cl_steal, .Lfunc_end1-cl_steal
                                        // -- End function
	.globl	cl_take                         // -- Begin function cl_take
	.p2align	2
	.type	cl_take,@function
cl_take:                                // @cl_take
// %bb.0:
	stp	x30, x21, [sp, #-32]!           // 16-byte Folded Spill
	stp	x20, x19, [sp, #16]             // 16-byte Folded Spill
	ldr	x19, [x0, #8]
	sub	x20, x19, #1
	str	x20, [x0, #8]
	dmb	ish
	ldr	x8, [x0]
	cmp	x8, x19
	b.ge	.LBB2_3
// %bb.1:
	and	x9, x20, #0x3ff
	cmp	x8, x20
	add	x9, x0, x9, lsl #2
	ldr	w9, [x9, #16]
	str	w9, [x1]
	b.ne	.LBB2_4
// %bb.2:
	mov	x21, x0
	mov	x0, x20
	mov	x1, x19
	mov	x2, x21
	bl	__aarch64_cas8_acq_rel
	cmp	x0, x20
	str	x19, [x21, #8]
	cset	w8, eq
	b	.LBB2_5
.LBB2_3:
	mov	w8, wzr
	str	x19, [x0, #8]
	b	.LBB2_5
.LBB2_4:
	mov	w8, #1                          // =0x1
.LBB2_5:
	ldp	x20, x19, [sp, #16]             // 16-byte Folded Reload
	mov	w0, w8
	ldp	x30, x21, [sp], #32             // 16-byte Folded Reload
	ret
.Lfunc_end2:
	.size	cl_take, .Lfunc_end2-cl_take
                                        // -- End function
	.ident	"Ubuntu clang version 18.1.3 (1ubuntu1)"
	.section	".note.GNU-stack","",@progbits
	.addrsig
