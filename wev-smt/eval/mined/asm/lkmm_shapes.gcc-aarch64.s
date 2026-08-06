	.arch armv8-a
	.file	"lkmm_shapes.c"
	.text
	.align	2
	.p2align 4,,11
	.global	mp_writer
	.type	mp_writer, %function
mp_writer:
.LFB0:
	.cfi_startproc
	mov	w2, 42
	str	w2, [x0]
	mov	w0, 1
	stlr	w0, [x1]
	ret
	.cfi_endproc
.LFE0:
	.size	mp_writer, .-mp_writer
	.align	2
	.p2align 4,,11
	.global	mp_reader
	.type	mp_reader, %function
mp_reader:
.LFB1:
	.cfi_startproc
	ldar	w1, [x1]
	cbz	w1, .L5
	ldr	w0, [x0]
	ret
	.p2align 2,,3
.L5:
	mov	w0, -1
	ret
	.cfi_endproc
.LFE1:
	.size	mp_reader, .-mp_reader
	.align	2
	.p2align 4,,11
	.global	lb_thread_data
	.type	lb_thread_data, %function
lb_thread_data:
.LFB2:
	.cfi_startproc
	ldr	w0, [x0]
	str	w0, [x1]
	ret
	.cfi_endproc
.LFE2:
	.size	lb_thread_data, .-lb_thread_data
	.align	2
	.p2align 4,,11
	.global	lb_thread_ctrl
	.type	lb_thread_ctrl, %function
lb_thread_ctrl:
.LFB3:
	.cfi_startproc
	ldr	w0, [x0]
	cbnz	w0, .L12
	ret
	.p2align 2,,3
.L12:
	mov	w0, 1
	str	w0, [x1]
	ret
	.cfi_endproc
.LFE3:
	.size	lb_thread_ctrl, .-lb_thread_ctrl
	.align	2
	.p2align 4,,11
	.global	lb_thread_addr
	.type	lb_thread_addr, %function
lb_thread_addr:
.LFB4:
	.cfi_startproc
	ldr	x0, [x0]
	cmp	x0, 0
	csel	x1, x1, x0, eq
	ldr	w0, [x1]
	ret
	.cfi_endproc
.LFE4:
	.size	lb_thread_addr, .-lb_thread_addr
	.align	2
	.p2align 4,,11
	.global	sb_thread
	.type	sb_thread, %function
sb_thread:
.LFB5:
	.cfi_startproc
	mov	w2, 1
	str	w2, [x0]
	ldr	w0, [x1]
	ret
	.cfi_endproc
.LFE5:
	.size	sb_thread, .-sb_thread
	.align	2
	.p2align 4,,11
	.global	wrc_p1
	.type	wrc_p1, %function
wrc_p1:
.LFB6:
	.cfi_startproc
	ldr	w0, [x0]
	stlr	w0, [x1]
	ret
	.cfi_endproc
.LFE6:
	.size	wrc_p1, .-wrc_p1
	.align	2
	.p2align 4,,11
	.global	isa2_p1
	.type	isa2_p1, %function
isa2_p1:
.LFB7:
	.cfi_startproc
	ldar	w0, [x0]
	stlr	w0, [x1]
	ret
	.cfi_endproc
.LFE7:
	.size	isa2_p1, .-isa2_p1
	.align	2
	.p2align 4,,11
	.global	r_p0
	.type	r_p0, %function
r_p0:
.LFB8:
	.cfi_startproc
	mov	w2, 1
	str	w2, [x0]
	stlr	w2, [x1]
	ret
	.cfi_endproc
.LFE8:
	.size	r_p0, .-r_p0
	.align	2
	.p2align 4,,11
	.global	s_p1
	.type	s_p1, %function
s_p1:
.LFB9:
	.cfi_startproc
	ldr	w0, [x0]
	add	w0, w0, 1
	str	w0, [x1]
	ret
	.cfi_endproc
.LFE9:
	.size	s_p1, .-s_p1
	.ident	"GCC: (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0"
	.section	.note.GNU-stack,"",@progbits
