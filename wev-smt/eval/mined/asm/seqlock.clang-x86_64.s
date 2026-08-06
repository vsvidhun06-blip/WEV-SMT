	.text
	.file	"seqlock.c"
	.globl	seq_write                       # -- Begin function seq_write
	.p2align	4, 0x90
	.type	seq_write,@function
seq_write:                              # @seq_write
# %bb.0:
	movl	(%rdi), %eax
	leal	1(%rax), %ecx
	movl	%ecx, (%rdi)
	#MEMBARRIER
	movl	%esi, 4(%rdi)
	movl	%edx, 8(%rdi)
	#MEMBARRIER
	addl	$2, %eax
	movl	%eax, (%rdi)
	retq
.Lfunc_end0:
	.size	seq_write, .Lfunc_end0-seq_write
                                        # -- End function
	.globl	seq_read                        # -- Begin function seq_read
	.p2align	4, 0x90
	.type	seq_read,@function
seq_read:                               # @seq_read
# %bb.0:
                                        # implicit-def: $eax
	jmp	.LBB1_1
	.p2align	4, 0x90
.LBB1_3:                                #   in Loop: Header=BB1_1 Depth=1
	cmpl	%eax, %ecx
	je	.LBB1_4
.LBB1_1:                                # =>This Inner Loop Header: Depth=1
	movl	(%rdi), %ecx
	testb	$1, %cl
	jne	.LBB1_3
# %bb.2:                                #   in Loop: Header=BB1_1 Depth=1
	movl	4(%rdi), %eax
	movl	%eax, (%rsi)
	movl	8(%rdi), %eax
	movl	%eax, (%rdx)
	#MEMBARRIER
	movl	(%rdi), %eax
	jmp	.LBB1_3
.LBB1_4:
	movl	$1, %eax
	retq
.Lfunc_end1:
	.size	seq_read, .Lfunc_end1-seq_read
                                        # -- End function
	.globl	seq_read_report                 # -- Begin function seq_read_report
	.p2align	4, 0x90
	.type	seq_read_report,@function
seq_read_report:                        # @seq_read_report
# %bb.0:
	movl	(%rdi), %eax
	movl	(%rdi), %ecx
	subl	%eax, %ecx
	movl	%ecx, (%rsi)
	retq
.Lfunc_end2:
	.size	seq_read_report, .Lfunc_end2-seq_read_report
                                        # -- End function
	.ident	"Ubuntu clang version 18.1.3 (1ubuntu1)"
	.section	".note.GNU-stack","",@progbits
	.addrsig
