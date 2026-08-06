	.file	"ms_queue.c"
	.text
	.p2align 4
	.globl	ms_enqueue
	.type	ms_enqueue, @function
ms_enqueue:
	endbr64
	movq	$0, (%rsi)
	leaq	8(%rdi), %rcx
	.p2align 4,,10
	.p2align 3
.L3:
	movq	(%rcx), %rdx
	movq	(%rdx), %r8
	movq	(%rcx), %rdi
	cmpq	%rdi, %rdx
	jne	.L3
	testq	%r8, %r8
	jne	.L4
	movq	%r8, %rax
	lock cmpxchgq	%rsi, (%rdx)
	jne	.L3
	movq	%rdx, %rax
	lock cmpxchgq	%rsi, (%rcx)
	ret
	.p2align 4,,10
	.p2align 3
.L4:
	movq	%rdx, %rax
	lock cmpxchgq	%r8, (%rcx)
	jmp	.L3
	.size	ms_enqueue, .-ms_enqueue
	.p2align 4
	.globl	ms_dequeue
	.type	ms_dequeue, @function
ms_dequeue:
	endbr64
	leaq	8(%rdi), %r8
.L14:
	movq	(%rdi), %rax
	movq	(%r8), %r9
	movq	(%rax), %rdx
	movq	(%rdi), %rcx
	cmpq	%rcx, %rax
	jne	.L14
	testq	%rdx, %rdx
	je	.L18
	cmpq	%r9, %rax
	je	.L23
	movl	8(%rdx), %ecx
	movl	%ecx, (%rsi)
	lock cmpxchgq	%rdx, (%rdi)
	jne	.L14
	movl	$1, %eax
	ret
	.p2align 4,,10
	.p2align 3
.L23:
	lock cmpxchgq	%rdx, (%r8)
	jmp	.L14
	.p2align 4,,10
	.p2align 3
.L18:
	xorl	%eax, %eax
	ret
	.size	ms_dequeue, .-ms_dequeue
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
