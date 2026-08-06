	.file	"lkmm_fakedep.c"
	.text
	.p2align 4
	.globl	lb_xor
	.type	lb_xor, @function
lb_xor:
	endbr64
	movl	(%rdi), %eax
	movl	$1, (%rsi)
	ret
	.size	lb_xor, .-lb_xor
	.p2align 4
	.globl	lb_linear
	.type	lb_linear, @function
lb_linear:
	endbr64
	movl	(%rdi), %eax
	movl	$1, (%rsi)
	ret
	.size	lb_linear, .-lb_linear
	.p2align 4
	.globl	lb_sub
	.type	lb_sub, @function
lb_sub:
	endbr64
	movl	(%rdi), %eax
	movl	$1, (%rsi)
	ret
	.size	lb_sub, .-lb_sub
	.p2align 4
	.globl	lb_andzero
	.type	lb_andzero, @function
lb_andzero:
	endbr64
	movl	(%rdi), %eax
	movl	$1, (%rsi)
	ret
	.size	lb_andzero, .-lb_andzero
	.p2align 4
	.globl	lb_mulzero
	.type	lb_mulzero, @function
lb_mulzero:
	endbr64
	movl	(%rdi), %eax
	movl	$1, (%rsi)
	ret
	.size	lb_mulzero, .-lb_mulzero
	.p2align 4
	.globl	lb_selfand
	.type	lb_selfand, @function
lb_selfand:
	endbr64
	movl	(%rdi), %eax
	movl	%eax, (%rsi)
	ret
	.size	lb_selfand, .-lb_selfand
	.p2align 4
	.globl	lb_copy
	.type	lb_copy, @function
lb_copy:
	endbr64
	movl	(%rdi), %eax
	movl	%eax, (%rsi)
	ret
	.size	lb_copy, .-lb_copy
	.p2align 4
	.globl	lb_orcompl
	.type	lb_orcompl, @function
lb_orcompl:
	endbr64
	movl	(%rdi), %eax
	movl	$1, (%rsi)
	ret
	.size	lb_orcompl, .-lb_orcompl
	.p2align 4
	.globl	lb_fakectrl
	.type	lb_fakectrl, @function
lb_fakectrl:
	endbr64
	movl	(%rdi), %eax
	movl	$1, (%rsi)
	ret
	.size	lb_fakectrl, .-lb_fakectrl
	.p2align 4
	.globl	lb_realctrl
	.type	lb_realctrl, @function
lb_realctrl:
	endbr64
	movl	(%rdi), %eax
	cmpl	$1, %eax
	je	.L14
	movl	$2, (%rsi)
	ret
	.p2align 4,,10
	.p2align 3
.L14:
	movl	$1, (%rsi)
	ret
	.size	lb_realctrl, .-lb_realctrl
	.p2align 4
	.globl	lb_shiftmask
	.type	lb_shiftmask, @function
lb_shiftmask:
	endbr64
	movl	(%rdi), %eax
	movl	$1, (%rsi)
	ret
	.size	lb_shiftmask, .-lb_shiftmask
	.p2align 4
	.globl	lb_modone
	.type	lb_modone, @function
lb_modone:
	endbr64
	movl	(%rdi), %eax
	movl	$1, (%rsi)
	ret
	.size	lb_modone, .-lb_modone
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
