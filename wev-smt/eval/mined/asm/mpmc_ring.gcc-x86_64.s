	.file	"mpmc_ring.c"
	.text
	.p2align 4
	.globl	mpmc_push
	.type	mpmc_push, @function
mpmc_push:
	endbr64
.L10:
	movl	(%rdi), %eax
.L2:
	movl	%eax, %ecx
	andl	$63, %ecx
	leal	1(%rcx), %edx
	leaq	(%rdi,%rdx,8), %rdx
	movl	(%rdx), %r8d
	cmpl	%eax, %r8d
	jne	.L3
	leal	1(%rax), %r8d
	lock cmpxchgl	%r8d, (%rdi)
	jne	.L2
	movl	%esi, 12(%rdi,%rcx,8)
	addl	$1, %eax
	movl	%eax, (%rdx)
	movl	$1, %eax
	ret
	.p2align 4,,10
	.p2align 3
.L3:
	jns	.L10
	xorl	%eax, %eax
	ret
	.size	mpmc_push, .-mpmc_push
	.p2align 4
	.globl	mpmc_pop
	.type	mpmc_pop, @function
mpmc_pop:
	endbr64
	movl	4(%rdi), %eax
	movq	%rdi, %rdx
	leaq	4(%rdi), %r9
.L13:
	movl	%eax, %edi
	andl	$63, %edi
	leal	1(%rdi), %ecx
	leaq	(%rdx,%rcx,8), %r8
	movl	(%r8), %ecx
	subl	$1, %ecx
	cmpl	%eax, %ecx
	jne	.L14
	leal	1(%rax), %ecx
	lock cmpxchgl	%ecx, (%r9)
	jne	.L13
	movl	12(%rdx,%rdi,8), %edx
	addl	$64, %eax
	movl	%edx, (%rsi)
	movl	%eax, (%r8)
	movl	$1, %eax
	ret
	.p2align 4,,10
	.p2align 3
.L14:
	js	.L17
	movl	(%r9), %eax
	jmp	.L13
	.p2align 4,,10
	.p2align 3
.L17:
	xorl	%eax, %eax
	ret
	.size	mpmc_pop, .-mpmc_pop
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
