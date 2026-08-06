	.text
	.file	"chase_lev.c"
	.globl	cl_push                         # -- Begin function cl_push
	.p2align	4, 0x90
	.type	cl_push,@function
cl_push:                                # @cl_push
# %bb.0:
	movq	8(%rdi), %rax
	movq	(%rdi), %rcx
	movq	%rax, %rdx
	subq	%rcx, %rdx
	cmpq	$1023, %rdx                     # imm = 0x3FF
	jg	.LBB0_2
# %bb.1:
	movl	%eax, %ecx
	andl	$1023, %ecx                     # imm = 0x3FF
	movl	%esi, 16(%rdi,%rcx,4)
	#MEMBARRIER
	incq	%rax
	movq	%rax, 8(%rdi)
.LBB0_2:
	retq
.Lfunc_end0:
	.size	cl_push, .Lfunc_end0-cl_push
                                        # -- End function
	.globl	cl_steal                        # -- Begin function cl_steal
	.p2align	4, 0x90
	.type	cl_steal,@function
cl_steal:                               # @cl_steal
# %bb.0:
	movq	(%rdi), %rax
	mfence
	movq	8(%rdi), %rdx
	xorl	%ecx, %ecx
	cmpq	%rdx, %rax
	jge	.LBB1_3
# %bb.1:
	movl	%eax, %edx
	andl	$1023, %edx                     # imm = 0x3FF
	movl	16(%rdi,%rdx,4), %edx
	leaq	1(%rax), %r8
	lock		cmpxchgq	%r8, (%rdi)
	jne	.LBB1_3
# %bb.2:
	movl	%edx, (%rsi)
	movl	$1, %ecx
.LBB1_3:
	movl	%ecx, %eax
	retq
.Lfunc_end1:
	.size	cl_steal, .Lfunc_end1-cl_steal
                                        # -- End function
	.globl	cl_take                         # -- Begin function cl_take
	.p2align	4, 0x90
	.type	cl_take,@function
cl_take:                                # @cl_take
# %bb.0:
	movq	8(%rdi), %rdx
	leaq	-1(%rdx), %rax
	movq	%rax, 8(%rdi)
	mfence
	movq	(%rdi), %r8
	cmpq	%rdx, %r8
	jge	.LBB2_1
# %bb.2:
	movl	%eax, %ecx
	andl	$1023, %ecx                     # imm = 0x3FF
	movl	16(%rdi,%rcx,4), %ecx
	movl	%ecx, (%rsi)
	movl	$1, %ecx
	cmpq	%rax, %r8
	jne	.LBB2_4
# %bb.3:
	xorl	%ecx, %ecx
	lock		cmpxchgq	%rdx, (%rdi)
	sete	%cl
	movq	%rdx, 8(%rdi)
.LBB2_4:
	movl	%ecx, %eax
	retq
.LBB2_1:
	movq	%rdx, 8(%rdi)
	xorl	%eax, %eax
	retq
.Lfunc_end2:
	.size	cl_take, .Lfunc_end2-cl_take
                                        # -- End function
	.ident	"Ubuntu clang version 18.1.3 (1ubuntu1)"
	.section	".note.GNU-stack","",@progbits
	.addrsig
