	.text
	.file	"dclp_barrier.c"
	.globl	dclp_get                        # -- Begin function dclp_get
	.p2align	4, 0x90
	.type	dclp_get,@function
dclp_get:                               # @dclp_get
# %bb.0:
	movq	(%rdi), %rax
	testq	%rax, %rax
	je	.LBB0_1
# %bb.5:
	retq
	.p2align	4, 0x90
.LBB0_1:                                # =>This Inner Loop Header: Depth=1
	movl	$1, %eax
	xchgl	%eax, 8(%rdi)
	testl	%eax, %eax
	jne	.LBB0_1
# %bb.2:
	movq	(%rdi), %rax
	testq	%rax, %rax
	je	.LBB0_3
# %bb.4:
	movl	$0, 8(%rdi)
	retq
.LBB0_3:
	movq	%rsi, (%rdi)
	movq	%rsi, %rax
	movl	$0, 8(%rdi)
	retq
.Lfunc_end0:
	.size	dclp_get, .Lfunc_end0-dclp_get
                                        # -- End function
	.globl	barrier_wait                    # -- Begin function barrier_wait
	.p2align	4, 0x90
	.type	barrier_wait,@function
barrier_wait:                           # @barrier_wait
# %bb.0:
	xorb	$1, (%rdx)
	movl	$1, %eax
	lock		xaddl	%eax, (%rdi)
	incl	%eax
	cmpl	%esi, %eax
	jne	.LBB1_2
# %bb.1:
	movl	$0, (%rdi)
	movl	(%rdx), %eax
	movl	%eax, 4(%rdi)
	retq
	.p2align	4, 0x90
.LBB1_2:                                # =>This Inner Loop Header: Depth=1
	movl	4(%rdi), %eax
	cmpl	(%rdx), %eax
	jne	.LBB1_2
# %bb.3:
	retq
.Lfunc_end1:
	.size	barrier_wait, .Lfunc_end1-barrier_wait
                                        # -- End function
	.globl	rc_acquire                      # -- Begin function rc_acquire
	.p2align	4, 0x90
	.type	rc_acquire,@function
rc_acquire:                             # @rc_acquire
# %bb.0:
	lock		incl	(%rdi)
	retq
.Lfunc_end2:
	.size	rc_acquire, .Lfunc_end2-rc_acquire
                                        # -- End function
	.globl	rc_release                      # -- Begin function rc_release
	.p2align	4, 0x90
	.type	rc_release,@function
rc_release:                             # @rc_release
# %bb.0:
	xorl	%eax, %eax
	lock		decl	(%rdi)
	jne	.LBB3_2
# %bb.1:
	#MEMBARRIER
	movl	$1, %eax
.LBB3_2:
	retq
.Lfunc_end3:
	.size	rc_release, .Lfunc_end3-rc_release
                                        # -- End function
	.globl	rc_rebalance                    # -- Begin function rc_rebalance
	.p2align	4, 0x90
	.type	rc_rebalance,@function
rc_rebalance:                           # @rc_rebalance
# %bb.0:
	movl	(%rsi), %eax
	movl	$1, 4(%rdi)
	retq
.Lfunc_end4:
	.size	rc_rebalance, .Lfunc_end4-rc_rebalance
                                        # -- End function
	.ident	"Ubuntu clang version 18.1.3 (1ubuntu1)"
	.section	".note.GNU-stack","",@progbits
	.addrsig
