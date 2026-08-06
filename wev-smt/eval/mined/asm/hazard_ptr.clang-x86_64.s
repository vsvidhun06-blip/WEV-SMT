	.text
	.file	"hazard_ptr.c"
	.globl	hp_protect                      # -- Begin function hp_protect
	.p2align	4, 0x90
	.type	hp_protect,@function
hp_protect:                             # @hp_protect
# %bb.0:
	.p2align	4, 0x90
.LBB0_1:                                # =>This Inner Loop Header: Depth=1
	movq	(%rdi), %rax
	movq	%rax, %rcx
	xchgq	%rcx, 8(%rdi)
	movq	(%rdi), %rcx
	cmpq	%rcx, %rax
	jne	.LBB0_1
# %bb.2:
	retq
.Lfunc_end0:
	.size	hp_protect, .Lfunc_end0-hp_protect
                                        # -- End function
	.globl	hp_read                         # -- Begin function hp_read
	.p2align	4, 0x90
	.type	hp_read,@function
hp_read:                                # @hp_read
# %bb.0:
	.p2align	4, 0x90
.LBB1_1:                                # =>This Inner Loop Header: Depth=1
	movq	(%rdi), %rax
	movq	%rax, %rcx
	xchgq	%rcx, 8(%rdi)
	movq	(%rdi), %rcx
	cmpq	%rcx, %rax
	jne	.LBB1_1
# %bb.2:
	testq	%rax, %rax
	je	.LBB1_3
# %bb.4:
	movl	(%rax), %eax
	retq
.LBB1_3:
	movl	$-1, %eax
	retq
.Lfunc_end1:
	.size	hp_read, .Lfunc_end1-hp_read
                                        # -- End function
	.globl	hp_retire                       # -- Begin function hp_retire
	.p2align	4, 0x90
	.type	hp_retire,@function
hp_retire:                              # @hp_retire
# %bb.0:
	movq	8(%rdi), %rax
	cmpq	%rsi, %rax
	je	.LBB2_2
# %bb.1:
	movq	$0, (%rdi)
.LBB2_2:
	retq
.Lfunc_end2:
	.size	hp_retire, .Lfunc_end2-hp_retire
                                        # -- End function
	.ident	"Ubuntu clang version 18.1.3 (1ubuntu1)"
	.section	".note.GNU-stack","",@progbits
	.addrsig
