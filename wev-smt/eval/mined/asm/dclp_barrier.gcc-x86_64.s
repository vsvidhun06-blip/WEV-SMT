	.file	"dclp_barrier.c"
	.text
	.p2align 4
	.globl	dclp_get
	.type	dclp_get, @function
dclp_get:
	endbr64
	movq	(%rdi), %rax
	testq	%rax, %rax
	jne	.L1
	leaq	8(%rdi), %rax
	movl	$1, %ecx
	.p2align 4,,10
	.p2align 3
.L2:
	movl	%ecx, %edx
	xchgl	(%rax), %edx
	testl	%edx, %edx
	jne	.L2
	movq	(%rdi), %rax
	testq	%rax, %rax
	je	.L10
	movl	$0, 8(%rdi)
.L1:
	ret
	.p2align 4,,10
	.p2align 3
.L10:
	movq	%rsi, %rax
	movq	%rsi, (%rdi)
	movl	$0, 8(%rdi)
	ret
	.size	dclp_get, .-dclp_get
	.p2align 4
	.globl	barrier_wait
	.type	barrier_wait, @function
barrier_wait:
	endbr64
	xorl	$1, (%rdx)
	movl	$1, %eax
	lock xaddl	%eax, (%rdi)
	addl	$1, %eax
	cmpl	%eax, %esi
	je	.L16
	.p2align 4,,10
	.p2align 3
.L12:
	movl	4(%rdi), %eax
	cmpl	(%rdx), %eax
	jne	.L12
	ret
	.p2align 4,,10
	.p2align 3
.L16:
	movl	$0, (%rdi)
	movl	(%rdx), %eax
	movl	%eax, 4(%rdi)
	ret
	.size	barrier_wait, .-barrier_wait
	.p2align 4
	.globl	rc_acquire
	.type	rc_acquire, @function
rc_acquire:
	endbr64
	lock addl	$1, (%rdi)
	ret
	.size	rc_acquire, .-rc_acquire
	.p2align 4
	.globl	rc_release
	.type	rc_release, @function
rc_release:
	endbr64
	lock subl	$1, (%rdi)
	movl	$0, %eax
	je	.L22
	ret
	.p2align 4,,10
	.p2align 3
.L22:
	movl	$1, %eax
	ret
	.size	rc_release, .-rc_release
	.p2align 4
	.globl	rc_rebalance
	.type	rc_rebalance, @function
rc_rebalance:
	endbr64
	movl	(%rsi), %eax
	movl	$1, 4(%rdi)
	ret
	.size	rc_rebalance, .-rc_rebalance
	.ident	"GCC: (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0"
	.section	.note.GNU-stack,"",@progbits
	.section	.note.gnu.property,"a"
	.align 8
	.long	1f - 0f
	.long	4f - 1f
	.long	5
0:
	.string	"GNU"
1:
	.align 8
	.long	0xc0000002
	.long	3f - 2f
2:
	.long	0x3
3:
	.align 8
4:
