	.text
	.file	"ms_queue.c"
	.globl	ms_enqueue                      # -- Begin function ms_enqueue
	.p2align	4, 0x90
	.type	ms_enqueue,@function
ms_enqueue:                             # @ms_enqueue
# %bb.0:
	movq	$0, (%rsi)
	jmp	.LBB0_1
	.p2align	4, 0x90
.LBB0_5:                                #   in Loop: Header=BB0_1 Depth=1
	movq	%rcx, %rax
	lock		cmpxchgq	%rdx, 8(%rdi)
.LBB0_1:                                # =>This Inner Loop Header: Depth=1
	movq	8(%rdi), %rcx
	movq	(%rcx), %rdx
	movq	8(%rdi), %rax
	cmpq	%rax, %rcx
	jne	.LBB0_1
# %bb.2:                                #   in Loop: Header=BB0_1 Depth=1
	testq	%rdx, %rdx
	jne	.LBB0_5
# %bb.3:                                #   in Loop: Header=BB0_1 Depth=1
	xorl	%eax, %eax
	lock		cmpxchgq	%rsi, (%rcx)
	jne	.LBB0_1
# %bb.4:
	movq	%rcx, %rax
	lock		cmpxchgq	%rsi, 8(%rdi)
	retq
.Lfunc_end0:
	.size	ms_enqueue, .Lfunc_end0-ms_enqueue
                                        # -- End function
	.globl	ms_dequeue                      # -- Begin function ms_dequeue
	.p2align	4, 0x90
	.type	ms_dequeue,@function
ms_dequeue:                             # @ms_dequeue
# %bb.0:
	jmp	.LBB1_1
.LBB1_5:                                #   in Loop: Header=BB1_1 Depth=1
	movq	%rcx, %rax
	lock		cmpxchgq	%rdx, 8(%rdi)
	.p2align	4, 0x90
.LBB1_1:                                # =>This Inner Loop Header: Depth=1
	movq	(%rdi), %rax
	movq	8(%rdi), %rcx
	movq	(%rax), %rdx
	movq	(%rdi), %r8
	cmpq	%r8, %rax
	jne	.LBB1_1
# %bb.2:                                #   in Loop: Header=BB1_1 Depth=1
	testq	%rdx, %rdx
	je	.LBB1_3
# %bb.4:                                #   in Loop: Header=BB1_1 Depth=1
	cmpq	%rcx, %rax
	je	.LBB1_5
# %bb.6:                                #   in Loop: Header=BB1_1 Depth=1
	movl	8(%rdx), %ecx
	movl	%ecx, (%rsi)
	lock		cmpxchgq	%rdx, (%rdi)
	jne	.LBB1_1
# %bb.7:
	movl	$1, %eax
	retq
.LBB1_3:
	xorl	%eax, %eax
	retq
.Lfunc_end1:
	.size	ms_dequeue, .Lfunc_end1-ms_dequeue
                                        # -- End function
	.ident	"Ubuntu clang version 18.1.3 (1ubuntu1)"
	.section	".note.GNU-stack","",@progbits
	.addrsig
