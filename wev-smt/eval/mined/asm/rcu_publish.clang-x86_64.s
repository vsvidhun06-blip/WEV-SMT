	.text
	.file	"rcu_publish.c"
	.globl	rcu_publish                     # -- Begin function rcu_publish
	.p2align	4, 0x90
	.type	rcu_publish,@function
rcu_publish:                            # @rcu_publish
# %bb.0:
	movl	%edx, (%rsi)
	movl	%ecx, 4(%rsi)
	movq	%rsi, (%rdi)
	retq
.Lfunc_end0:
	.size	rcu_publish, .Lfunc_end0-rcu_publish
                                        # -- End function
	.globl	rcu_consume                     # -- Begin function rcu_consume
	.p2align	4, 0x90
	.type	rcu_consume,@function
rcu_consume:                            # @rcu_consume
# %bb.0:
	movq	(%rdi), %rax
	testq	%rax, %rax
	je	.LBB1_1
# %bb.3:
	movl	(%rax), %eax
	retq
.LBB1_1:
	movl	$-1, %eax
	retq
.Lfunc_end1:
	.size	rcu_consume, .Lfunc_end1-rcu_consume
                                        # -- End function
	.globl	rcu_consume_selfsub             # -- Begin function rcu_consume_selfsub
	.p2align	4, 0x90
	.type	rcu_consume_selfsub,@function
rcu_consume_selfsub:                    # @rcu_consume_selfsub
# %bb.0:
	movq	(%rdi), %rax
	movl	(%rsi), %eax
	retq
.Lfunc_end2:
	.size	rcu_consume_selfsub, .Lfunc_end2-rcu_consume_selfsub
                                        # -- End function
	.globl	rcu_consume_andzero             # -- Begin function rcu_consume_andzero
	.p2align	4, 0x90
	.type	rcu_consume_andzero,@function
rcu_consume_andzero:                    # @rcu_consume_andzero
# %bb.0:
	movq	(%rdi), %rax
	movl	(%rsi), %eax
	retq
.Lfunc_end3:
	.size	rcu_consume_andzero, .Lfunc_end3-rcu_consume_andzero
                                        # -- End function
	.globl	rcu_consume_selfxor             # -- Begin function rcu_consume_selfxor
	.p2align	4, 0x90
	.type	rcu_consume_selfxor,@function
rcu_consume_selfxor:                    # @rcu_consume_selfxor
# %bb.0:
	movq	(%rdi), %rax
	movl	(%rsi), %eax
	retq
.Lfunc_end4:
	.size	rcu_consume_selfxor, .Lfunc_end4-rcu_consume_selfxor
                                        # -- End function
	.globl	rcu_consume_cmp                 # -- Begin function rcu_consume_cmp
	.p2align	4, 0x90
	.type	rcu_consume_cmp,@function
rcu_consume_cmp:                        # @rcu_consume_cmp
# %bb.0:
	movq	(%rdi), %rcx
	movl	$-1, %eax
	cmpq	%rsi, %rcx
	je	.LBB5_1
# %bb.2:
	retq
.LBB5_1:
	movl	(%rsi), %eax
	retq
.Lfunc_end5:
	.size	rcu_consume_cmp, .Lfunc_end5-rcu_consume_cmp
                                        # -- End function
	.ident	"Ubuntu clang version 18.1.3 (1ubuntu1)"
	.section	".note.GNU-stack","",@progbits
	.addrsig
