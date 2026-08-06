	.text
	.file	"spsc_ring.c"
	.globl	spsc_push                       # -- Begin function spsc_push
	.p2align	4, 0x90
	.type	spsc_push,@function
spsc_push:                              # @spsc_push
# %bb.0:
	movl	(%rdi), %ecx
	movl	4(%rdi), %eax
	movl	%ecx, %edx
	subl	%eax, %edx
	xorl	%eax, %eax
	cmpl	$63, %edx
	ja	.LBB0_2
# %bb.1:
	movl	%ecx, %eax
	andl	$63, %eax
	movl	%esi, 8(%rdi,%rax,4)
	incl	%ecx
	movl	%ecx, (%rdi)
	movl	$1, %eax
.LBB0_2:
	retq
.Lfunc_end0:
	.size	spsc_push, .Lfunc_end0-spsc_push
                                        # -- End function
	.globl	spsc_pop                        # -- Begin function spsc_pop
	.p2align	4, 0x90
	.type	spsc_pop,@function
spsc_pop:                               # @spsc_pop
# %bb.0:
	movl	4(%rdi), %ecx
	movl	(%rdi), %edx
	xorl	%eax, %eax
	cmpl	%ecx, %edx
	je	.LBB1_2
# %bb.1:
	movl	%ecx, %eax
	andl	$63, %eax
	movl	8(%rdi,%rax,4), %eax
	movl	%eax, (%rsi)
	incl	%ecx
	movl	%ecx, 4(%rdi)
	movl	$1, %eax
.LBB1_2:
	retq
.Lfunc_end1:
	.size	spsc_pop, .Lfunc_end1-spsc_pop
                                        # -- End function
	.globl	spsc_pop_recycle                # -- Begin function spsc_pop_recycle
	.p2align	4, 0x90
	.type	spsc_pop_recycle,@function
spsc_pop_recycle:                       # @spsc_pop_recycle
# %bb.0:
	movl	4(%rdi), %ecx
	movl	(%rdi), %edx
	xorl	%eax, %eax
	cmpl	%ecx, %edx
	je	.LBB2_2
# %bb.1:
	movl	8(%rdi), %eax
	movl	%eax, (%rsi)
	incl	%ecx
	movl	%ecx, 4(%rdi)
	movl	$1, %eax
.LBB2_2:
	retq
.Lfunc_end2:
	.size	spsc_pop_recycle, .Lfunc_end2-spsc_pop_recycle
                                        # -- End function
	.ident	"Ubuntu clang version 18.1.3 (1ubuntu1)"
	.section	".note.GNU-stack","",@progbits
	.addrsig
