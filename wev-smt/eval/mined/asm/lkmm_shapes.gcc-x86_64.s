	.file	"lkmm_shapes.c"
	.text
	.p2align 4
	.globl	mp_writer
	.type	mp_writer, @function
mp_writer:
	endbr64
	movl	$42, (%rdi)
	movl	$1, (%rsi)
	ret
	.size	mp_writer, .-mp_writer
	.p2align 4
	.globl	mp_reader
	.type	mp_reader, @function
mp_reader:
	endbr64
	movl	(%rsi), %eax
	testl	%eax, %eax
	je	.L5
	movl	(%rdi), %eax
	ret
	.p2align 4,,10
	.p2align 3
.L5:
	movl	$-1, %eax
	ret
	.size	mp_reader, .-mp_reader
	.p2align 4
	.globl	lb_thread_data
	.type	lb_thread_data, @function
lb_thread_data:
	endbr64
	movl	(%rdi), %eax
	movl	%eax, (%rsi)
	ret
	.size	lb_thread_data, .-lb_thread_data
	.p2align 4
	.globl	lb_thread_ctrl
	.type	lb_thread_ctrl, @function
lb_thread_ctrl:
	endbr64
	movl	(%rdi), %eax
	testl	%eax, %eax
	jne	.L12
	ret
	.p2align 4,,10
	.p2align 3
.L12:
	movl	$1, (%rsi)
	ret
	.size	lb_thread_ctrl, .-lb_thread_ctrl
	.p2align 4
	.globl	lb_thread_addr
	.type	lb_thread_addr, @function
lb_thread_addr:
	endbr64
	movq	(%rdi), %rax
	testq	%rax, %rax
	je	.L16
	movl	(%rax), %eax
	ret
	.p2align 4,,10
	.p2align 3
.L16:
	movl	(%rsi), %eax
	ret
	.size	lb_thread_addr, .-lb_thread_addr
	.p2align 4
	.globl	sb_thread
	.type	sb_thread, @function
sb_thread:
	endbr64
	movl	$1, (%rdi)
	movl	(%rsi), %eax
	ret
	.size	sb_thread, .-sb_thread
	.p2align 4
	.globl	wrc_p1
	.type	wrc_p1, @function
wrc_p1:
	endbr64
	movl	(%rdi), %eax
	movl	%eax, (%rsi)
	ret
	.size	wrc_p1, .-wrc_p1
	.p2align 4
	.globl	isa2_p1
	.type	isa2_p1, @function
isa2_p1:
	endbr64
	movl	(%rdi), %eax
	movl	%eax, (%rsi)
	ret
	.size	isa2_p1, .-isa2_p1
	.p2align 4
	.globl	r_p0
	.type	r_p0, @function
r_p0:
	endbr64
	movl	$1, (%rdi)
	movl	$1, (%rsi)
	ret
	.size	r_p0, .-r_p0
	.p2align 4
	.globl	s_p1
	.type	s_p1, @function
s_p1:
	endbr64
	movl	(%rdi), %eax
	addl	$1, %eax
	movl	%eax, (%rsi)
	ret
	.size	s_p1, .-s_p1
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
