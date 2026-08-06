	.text
	.file	"hazard_ptr.c"
	.globl	hp_protect                      // -- Begin function hp_protect
	.p2align	2
	.type	hp_protect,@function
hp_protect:                             // @hp_protect
// %bb.0:
	mov	x8, x0
.LBB0_1:                                // =>This Inner Loop Header: Depth=1
	ldar	x0, [x8]
	add	x9, x8, #8
	stlr	x0, [x9]
	ldar	x9, [x8]
	cmp	x0, x9
	b.ne	.LBB0_1
// %bb.2:
	ret
.Lfunc_end0:
	.size	hp_protect, .Lfunc_end0-hp_protect
                                        // -- End function
	.globl	hp_read                         // -- Begin function hp_read
	.p2align	2
	.type	hp_read,@function
hp_read:                                // @hp_read
// %bb.0:
.LBB1_1:                                // =>This Inner Loop Header: Depth=1
	ldar	x8, [x0]
	add	x9, x0, #8
	stlr	x8, [x9]
	ldar	x9, [x0]
	cmp	x8, x9
	b.ne	.LBB1_1
// %bb.2:
	cbz	x8, .LBB1_4
// %bb.3:
	ldr	w0, [x8]
	ret
.LBB1_4:
	mov	w0, #-1                         // =0xffffffff
	ret
.Lfunc_end1:
	.size	hp_read, .Lfunc_end1-hp_read
                                        // -- End function
	.globl	hp_retire                       // -- Begin function hp_retire
	.p2align	2
	.type	hp_retire,@function
hp_retire:                              // @hp_retire
// %bb.0:
	add	x8, x0, #8
	ldar	x8, [x8]
	cmp	x8, x1
	b.eq	.LBB2_2
// %bb.1:
	stlr	xzr, [x0]
.LBB2_2:
	ret
.Lfunc_end2:
	.size	hp_retire, .Lfunc_end2-hp_retire
                                        // -- End function
	.ident	"Ubuntu clang version 18.1.3 (1ubuntu1)"
	.section	".note.GNU-stack","",@progbits
	.addrsig
