	.text
	.file	"rcu_publish.c"
	.globl	rcu_publish                     // -- Begin function rcu_publish
	.p2align	2
	.type	rcu_publish,@function
rcu_publish:                            // @rcu_publish
// %bb.0:
	stp	w2, w3, [x1]
	stlr	x1, [x0]
	ret
.Lfunc_end0:
	.size	rcu_publish, .Lfunc_end0-rcu_publish
                                        // -- End function
	.globl	rcu_consume                     // -- Begin function rcu_consume
	.p2align	2
	.type	rcu_consume,@function
rcu_consume:                            // @rcu_consume
// %bb.0:
	ldar	x8, [x0]
	cbz	x8, .LBB1_2
// %bb.1:
	ldr	w0, [x8]
	ret
.LBB1_2:
	mov	w0, #-1                         // =0xffffffff
	ret
.Lfunc_end1:
	.size	rcu_consume, .Lfunc_end1-rcu_consume
                                        // -- End function
	.globl	rcu_consume_selfsub             // -- Begin function rcu_consume_selfsub
	.p2align	2
	.type	rcu_consume_selfsub,@function
rcu_consume_selfsub:                    // @rcu_consume_selfsub
// %bb.0:
	ldar	xzr, [x0]
	ldr	w0, [x1]
	ret
.Lfunc_end2:
	.size	rcu_consume_selfsub, .Lfunc_end2-rcu_consume_selfsub
                                        // -- End function
	.globl	rcu_consume_andzero             // -- Begin function rcu_consume_andzero
	.p2align	2
	.type	rcu_consume_andzero,@function
rcu_consume_andzero:                    // @rcu_consume_andzero
// %bb.0:
	ldar	xzr, [x0]
	ldr	w0, [x1]
	ret
.Lfunc_end3:
	.size	rcu_consume_andzero, .Lfunc_end3-rcu_consume_andzero
                                        // -- End function
	.globl	rcu_consume_selfxor             // -- Begin function rcu_consume_selfxor
	.p2align	2
	.type	rcu_consume_selfxor,@function
rcu_consume_selfxor:                    // @rcu_consume_selfxor
// %bb.0:
	ldar	xzr, [x0]
	ldr	w0, [x1]
	ret
.Lfunc_end4:
	.size	rcu_consume_selfxor, .Lfunc_end4-rcu_consume_selfxor
                                        // -- End function
	.globl	rcu_consume_cmp                 // -- Begin function rcu_consume_cmp
	.p2align	2
	.type	rcu_consume_cmp,@function
rcu_consume_cmp:                        // @rcu_consume_cmp
// %bb.0:
	ldar	x8, [x0]
	cmp	x8, x1
	b.eq	.LBB5_2
// %bb.1:
	mov	w0, #-1                         // =0xffffffff
	ret
.LBB5_2:
	ldr	w0, [x1]
	ret
.Lfunc_end5:
	.size	rcu_consume_cmp, .Lfunc_end5-rcu_consume_cmp
                                        // -- End function
	.ident	"Ubuntu clang version 18.1.3 (1ubuntu1)"
	.section	".note.GNU-stack","",@progbits
	.addrsig
