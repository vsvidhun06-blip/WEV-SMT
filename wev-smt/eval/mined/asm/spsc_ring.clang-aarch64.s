	.text
	.file	"spsc_ring.c"
	.globl	spsc_push                       // -- Begin function spsc_push
	.p2align	2
	.type	spsc_push,@function
spsc_push:                              // @spsc_push
// %bb.0:
	add	x9, x0, #4
	ldr	w8, [x0]
	ldar	w9, [x9]
	sub	w9, w8, w9
	cmp	w9, #63
	b.ls	.LBB0_2
// %bb.1:
	mov	w0, wzr
	ret
.LBB0_2:
	and	x9, x8, #0x3f
	add	w8, w8, #1
	add	x9, x0, x9, lsl #2
	str	w1, [x9, #8]
	stlr	w8, [x0]
	mov	w0, #1                          // =0x1
	ret
.Lfunc_end0:
	.size	spsc_push, .Lfunc_end0-spsc_push
                                        // -- End function
	.globl	spsc_pop                        // -- Begin function spsc_pop
	.p2align	2
	.type	spsc_pop,@function
spsc_pop:                               // @spsc_pop
// %bb.0:
	ldr	w8, [x0, #4]
	ldar	w9, [x0]
	cmp	w9, w8
	b.ne	.LBB1_2
// %bb.1:
	mov	w0, wzr
	ret
.LBB1_2:
	and	x9, x8, #0x3f
	add	w8, w8, #1
	add	x10, x0, #4
	add	x9, x0, x9, lsl #2
	mov	w0, #1                          // =0x1
	ldr	w9, [x9, #8]
	str	w9, [x1]
	stlr	w8, [x10]
	ret
.Lfunc_end1:
	.size	spsc_pop, .Lfunc_end1-spsc_pop
                                        // -- End function
	.globl	spsc_pop_recycle                // -- Begin function spsc_pop_recycle
	.p2align	2
	.type	spsc_pop_recycle,@function
spsc_pop_recycle:                       // @spsc_pop_recycle
// %bb.0:
	ldr	w8, [x0, #4]
	ldar	w9, [x0]
	cmp	w9, w8
	b.ne	.LBB2_2
// %bb.1:
	mov	w0, wzr
	ret
.LBB2_2:
	ldr	w9, [x0, #8]
	add	w8, w8, #1
	add	x10, x0, #4
	mov	w0, #1                          // =0x1
	str	w9, [x1]
	stlr	w8, [x10]
	ret
.Lfunc_end2:
	.size	spsc_pop_recycle, .Lfunc_end2-spsc_pop_recycle
                                        // -- End function
	.ident	"Ubuntu clang version 18.1.3 (1ubuntu1)"
	.section	".note.GNU-stack","",@progbits
	.addrsig
