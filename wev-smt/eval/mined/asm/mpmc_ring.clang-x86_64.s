	.text
	.file	"mpmc_ring.c"
	.globl	mpmc_push                       # -- Begin function mpmc_push
	.p2align	4, 0x90
	.type	mpmc_push,@function
mpmc_push:                              # @mpmc_push
# %bb.0:
.LBB0_1:                                # =>This Loop Header: Depth=1
                                        #     Child Loop BB0_2 Depth 2
	movl	(%rdi), %eax
	.p2align	4, 0x90
.LBB0_2:                                #   Parent Loop BB0_1 Depth=1
                                        # =>  This Inner Loop Header: Depth=2
	movl	%eax, %ecx
	andl	$63, %ecx
	movl	8(%rdi,%rcx,8), %edx
	cmpl	%eax, %edx
	jne	.LBB0_5
# %bb.3:                                #   in Loop: Header=BB0_2 Depth=2
	leal	1(%rax), %edx
                                        # kill: def $eax killed $eax killed $rax
	lock		cmpxchgl	%edx, (%rdi)
                                        # kill: def $eax killed $eax def $rax
	jne	.LBB0_2
	jmp	.LBB0_4
	.p2align	4, 0x90
.LBB0_5:                                #   in Loop: Header=BB0_1 Depth=1
	jns	.LBB0_1
# %bb.6:
	xorl	%eax, %eax
	retq
.LBB0_4:
	movl	%esi, 12(%rdi,%rcx,8)
	movl	%edx, 8(%rdi,%rcx,8)
	movl	$1, %eax
	retq
.Lfunc_end0:
	.size	mpmc_push, .Lfunc_end0-mpmc_push
                                        # -- End function
	.globl	mpmc_pop                        # -- Begin function mpmc_pop
	.p2align	4, 0x90
	.type	mpmc_pop,@function
mpmc_pop:                               # @mpmc_pop
# %bb.0:
.LBB1_1:                                # =>This Loop Header: Depth=1
                                        #     Child Loop BB1_2 Depth 2
	movl	4(%rdi), %eax
	.p2align	4, 0x90
.LBB1_2:                                #   Parent Loop BB1_1 Depth=1
                                        # =>  This Inner Loop Header: Depth=2
	movl	%eax, %ecx
	movl	%eax, %edx
	andl	$63, %edx
	movl	8(%rdi,%rdx,8), %r8d
	leal	1(%rcx), %eax
	cmpl	%eax, %r8d
	jne	.LBB1_5
# %bb.3:                                #   in Loop: Header=BB1_2 Depth=2
	movl	%ecx, %eax
	lock		cmpxchgl	%r8d, 4(%rdi)
	jne	.LBB1_2
	jmp	.LBB1_4
	.p2align	4, 0x90
.LBB1_5:                                #   in Loop: Header=BB1_1 Depth=1
	jns	.LBB1_1
# %bb.6:
	xorl	%eax, %eax
	retq
.LBB1_4:
	movl	12(%rdi,%rdx,8), %eax
	movl	%eax, (%rsi)
	addl	$64, %ecx
	movl	%ecx, 8(%rdi,%rdx,8)
	movl	$1, %eax
	retq
.Lfunc_end1:
	.size	mpmc_pop, .Lfunc_end1-mpmc_pop
                                        # -- End function
	.ident	"Ubuntu clang version 18.1.3 (1ubuntu1)"
	.section	".note.GNU-stack","",@progbits
	.addrsig
