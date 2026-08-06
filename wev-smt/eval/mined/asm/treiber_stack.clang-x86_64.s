	.text
	.file	"treiber_stack.c"
	.globl	treiber_push                    # -- Begin function treiber_push
	.p2align	4, 0x90
	.type	treiber_push,@function
treiber_push:                           # @treiber_push
# %bb.0:
	movq	(%rdi), %rax
	.p2align	4, 0x90
.LBB0_1:                                # =>This Inner Loop Header: Depth=1
	movq	%rax, (%rsi)
	lock		cmpxchgq	%rsi, (%rdi)
	jne	.LBB0_1
# %bb.2:
	retq
.Lfunc_end0:
	.size	treiber_push, .Lfunc_end0-treiber_push
                                        # -- End function
	.globl	treiber_pop                     # -- Begin function treiber_pop
	.p2align	4, 0x90
	.type	treiber_pop,@function
treiber_pop:                            # @treiber_pop
# %bb.0:
	movq	(%rdi), %rax
	xorl	%ecx, %ecx
	.p2align	4, 0x90
.LBB1_1:                                # =>This Inner Loop Header: Depth=1
	testq	%rax, %rax
	je	.LBB1_4
# %bb.2:                                #   in Loop: Header=BB1_1 Depth=1
	movq	%rax, %rdx
	movq	(%rax), %rsi
	lock		cmpxchgq	%rsi, (%rdi)
	jne	.LBB1_1
# %bb.3:
	movq	%rdx, %rcx
.LBB1_4:
	movq	%rcx, %rax
	retq
.Lfunc_end1:
	.size	treiber_pop, .Lfunc_end1-treiber_pop
                                        # -- End function
	.globl	treiber_push_tagged             # -- Begin function treiber_push_tagged
	.p2align	4, 0x90
	.type	treiber_push_tagged,@function
treiber_push_tagged:                    # @treiber_push_tagged
# %bb.0:
	movq	(%rdi), %rax
	shlq	$16, %rsi
	orq	$1, %rsi
	movq	%rsi, (%rdi)
	retq
.Lfunc_end2:
	.size	treiber_push_tagged, .Lfunc_end2-treiber_push_tagged
                                        # -- End function
	.ident	"Ubuntu clang version 18.1.3 (1ubuntu1)"
	.section	".note.GNU-stack","",@progbits
	.addrsig
