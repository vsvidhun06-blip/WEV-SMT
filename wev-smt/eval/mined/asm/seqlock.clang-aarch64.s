	.text
	.file	"seqlock.c"
	.globl	seq_write                       // -- Begin function seq_write
	.p2align	2
	.type	seq_write,@function
seq_write:                              // @seq_write
// %bb.0:
	ldr	w8, [x0]
	add	w9, w8, #1
	add	w8, w8, #2
	str	w9, [x0]
	dmb	ish
	str	w1, [x0, #4]
	str	w2, [x0, #8]
	dmb	ish
	str	w8, [x0]
	ret
.Lfunc_end0:
	.size	seq_write, .Lfunc_end0-seq_write
                                        // -- End function
	.globl	seq_read                        // -- Begin function seq_read
	.p2align	2
	.type	seq_read,@function
seq_read:                               // @seq_read
// %bb.0:
                                        // implicit-def: $w8
	b	.LBB1_2
.LBB1_1:                                //   in Loop: Header=BB1_2 Depth=1
	cmp	w9, w8
	b.eq	.LBB1_4
.LBB1_2:                                // =>This Inner Loop Header: Depth=1
	ldar	w9, [x0]
	tbnz	w9, #0, .LBB1_1
// %bb.3:                               //   in Loop: Header=BB1_2 Depth=1
	ldr	w8, [x0, #4]
	str	w8, [x1]
	ldr	w8, [x0, #8]
	str	w8, [x2]
	dmb	ishld
	ldr	w8, [x0]
	b	.LBB1_1
.LBB1_4:
	mov	w0, #1                          // =0x1
	ret
.Lfunc_end1:
	.size	seq_read, .Lfunc_end1-seq_read
                                        // -- End function
	.globl	seq_read_report                 // -- Begin function seq_read_report
	.p2align	2
	.type	seq_read_report,@function
seq_read_report:                        // @seq_read_report
// %bb.0:
	ldar	w8, [x0]
	ldr	w9, [x0]
	sub	w8, w9, w8
	str	w8, [x1]
	ret
.Lfunc_end2:
	.size	seq_read_report, .Lfunc_end2-seq_read_report
                                        // -- End function
	.ident	"Ubuntu clang version 18.1.3 (1ubuntu1)"
	.section	".note.GNU-stack","",@progbits
	.addrsig
