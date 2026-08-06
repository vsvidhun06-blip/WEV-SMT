	.file	"chase_lev.c"
	.text
	.p2align 4
	.globl	cl_push
	.type	cl_push, @function
cl_push:
	endbr64
	movq	8(%rdi), %rax
	movq	(%rdi), %rcx
	movq	%rax, %rdx
	subq	%rcx, %rdx
	cmpq	$1023, %rdx
	jle	.L4
	ret
	.p2align 4,,10
	.p2align 3
.L4:
	movq	%rax, %rdx
	addq	$1, %rax
	andl	$1023, %edx
	addq	$4, %rdx
	movl	%esi, (%rdi,%rdx,4)
	movq	%rax, 8(%rdi)
	ret
	.size	cl_push, .-cl_push
	.p2align 4
	.globl	cl_steal
	.type	cl_steal, @function
cl_steal:
	endbr64
	movq	(%rdi), %rax
	lock orq	$0, (%rsp)
	movq	8(%rdi), %rdx
	cmpq	%rdx, %rax
	jl	.L6
.L8:
	xorl	%eax, %eax
	ret
	.p2align 4,,10
	.p2align 3
.L6:
	movq	%rax, %rdx
	leaq	1(%rax), %rcx
	andl	$1023, %edx
	addq	$4, %rdx
	movl	(%rdi,%rdx,4), %edx
	lock cmpxchgq	%rcx, (%rdi)
	jne	.L8
	movl	%edx, (%rsi)
	movl	$1, %eax
	ret
	.size	cl_steal, .-cl_steal
	.p2align 4
	.globl	cl_take
	.type	cl_take, @function
cl_take:
	endbr64
	movq	8(%rdi), %rcx
	leaq	-1(%rcx), %rdx
	movq	%rdx, 8(%rdi)
	lock orq	$0, (%rsp)
	movq	(%rdi), %rax
	cmpq	%rdx, %rax
	jg	.L20
	movq	%rdx, %r8
	andl	$1023, %r8d
	addq	$4, %r8
	movl	(%rdi,%r8,4), %r8d
	movl	%r8d, (%rsi)
	cmpq	%rdx, %rax
	je	.L15
	movl	$1, %eax
	ret
	.p2align 4,,10
	.p2align 3
.L15:
	lock cmpxchgq	%rcx, (%rdi)
	movq	%rcx, 8(%rdi)
	jne	.L14
	movl	$1, %eax
	ret
	.p2align 4,,10
	.p2align 3
.L20:
	movq	%rcx, 8(%rdi)
.L14:
	xorl	%eax, %eax
	ret
	.size	cl_take, .-cl_take
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
