	.file	"locks.c"
	.text
	.p2align 4
	.globl	dekker_p0
	.type	dekker_p0, @function
dekker_p0:
	endbr64
	movl	$1, (%rdi)
	xorl	%eax, %eax
	movl	4(%rdi), %edx
	testl	%edx, %edx
	je	.L5
	ret
	.p2align 4,,10
	.p2align 3
.L5:
	movl	$1, 12(%rdi)
	movl	$1, %eax
	ret
	.size	dekker_p0, .-dekker_p0
	.p2align 4
	.globl	peterson_p0
	.type	peterson_p0, @function
peterson_p0:
	endbr64
	movl	$1, (%rdi)
	movl	$0, 8(%rdi)
	movl	4(%rdi), %eax
	movl	8(%rdi), %edx
	testl	%eax, %eax
	je	.L10
	xorl	%eax, %eax
	testl	%edx, %edx
	jne	.L10
	ret
	.p2align 4,,10
	.p2align 3
.L10:
	movl	$1, 12(%rdi)
	movl	$1, %eax
	ret
	.size	peterson_p0, .-peterson_p0
	.p2align 4
	.globl	ticket_lock
	.type	ticket_lock, @function
ticket_lock:
	endbr64
	movl	$1, %edx
	lock xaddl	%edx, (%rdi)
	.p2align 4,,10
	.p2align 3
.L13:
	movl	4(%rdi), %eax
	cmpl	%edx, %eax
	jne	.L13
	ret
	.size	ticket_lock, .-ticket_lock
	.p2align 4
	.globl	ticket_unlock
	.type	ticket_unlock, @function
ticket_unlock:
	endbr64
	movl	4(%rdi), %eax
	addl	$1, %eax
	movl	%eax, 4(%rdi)
	ret
	.size	ticket_unlock, .-ticket_unlock
	.p2align 4
	.globl	ticket_waiters
	.type	ticket_waiters, @function
ticket_waiters:
	endbr64
	movl	(%rdi), %eax
	movl	4(%rdi), %edx
	subl	%edx, %eax
	ret
	.size	ticket_waiters, .-ticket_waiters
	.p2align 4
	.globl	mcs_acquire
	.type	mcs_acquire, @function
mcs_acquire:
	endbr64
	movq	$0, (%rsi)
	movq	%rsi, %rax
	xchgq	(%rdi), %rax
	testq	%rax, %rax
	jne	.L24
	ret
	.p2align 4,,10
	.p2align 3
.L24:
	movl	$1, 8(%rsi)
	leaq	8(%rsi), %rdx
	movq	%rsi, (%rax)
	.p2align 4,,10
	.p2align 3
.L19:
	movl	(%rdx), %eax
	testl	%eax, %eax
	jne	.L19
	ret
	.size	mcs_acquire, .-mcs_acquire
	.p2align 4
	.globl	mcs_release
	.type	mcs_release, @function
mcs_release:
	endbr64
	movq	(%rsi), %rax
	testq	%rax, %rax
	jne	.L30
	ret
	.p2align 4,,10
	.p2align 3
.L30:
	movl	$0, 8(%rax)
	ret
	.size	mcs_release, .-mcs_release
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
