	.file	"opaque_fakedep.c"
	.text
	.p2align 4
	.globl	ofd_volatile_xor
	.type	ofd_volatile_xor, @function
ofd_volatile_xor:
	endbr64
	movl	(%rdi), %eax
	movl	(%rdi), %eax
	movl	$1, (%rsi)
	ret
	.size	ofd_volatile_xor, .-ofd_volatile_xor
	.p2align 4
	.globl	ofd_hidden_sub
	.type	ofd_hidden_sub, @function
ofd_hidden_sub:
	endbr64
	movl	(%rdi), %eax
	movl	%eax, %edx
	subl	%edx, %eax
	addl	$1, %eax
	movl	%eax, (%rsi)
	ret
	.size	ofd_hidden_sub, .-ofd_hidden_sub
	.p2align 4
	.globl	ofd_hidden_andzero
	.type	ofd_hidden_andzero, @function
ofd_hidden_andzero:
	endbr64
	movl	(%rdi), %eax
	andl	%edx, %eax
	addl	$1, %eax
	movl	%eax, (%rsi)
	ret
	.size	ofd_hidden_andzero, .-ofd_hidden_andzero
	.p2align 4
	.globl	ofd_opaque_cancel
	.type	ofd_opaque_cancel, @function
ofd_opaque_cancel:
	endbr64
	pushq	%r12
	pushq	%rbp
	movq	%rsi, %rbp
	pushq	%rbx
	movl	(%rdi), %r12d
	movl	%r12d, %edi
	call	opaque_identity@PLT
	movl	%r12d, %edi
	movl	%eax, %ebx
	call	opaque_identity@PLT
	subl	%eax, %ebx
	addl	$1, %ebx
	movl	%ebx, 0(%rbp)
	popq	%rbx
	popq	%rbp
	popq	%r12
	ret
	.size	ofd_opaque_cancel, .-ofd_opaque_cancel
	.p2align 4
	.globl	ofd_hidden_xor
	.type	ofd_hidden_xor, @function
ofd_hidden_xor:
	endbr64
	movl	(%rdi), %edx
	movl	%edx, %eax
	xorl	%edx, %eax
	orl	$1, %eax
	movl	%eax, (%rsi)
	ret
	.size	ofd_hidden_xor, .-ofd_hidden_xor
	.p2align 4
	.globl	ofd_hidden_real
	.type	ofd_hidden_real, @function
ofd_hidden_real:
	endbr64
	movl	(%rdi), %eax
	addl	$1, %eax
	movl	%eax, (%rsi)
	ret
	.size	ofd_hidden_real, .-ofd_hidden_real
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
