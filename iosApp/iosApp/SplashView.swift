import UIKit

/// Açılış logosu (Android 12+ açılışındaki splash_logo_animated'a dayanır):
/// önce kendini çizen halka, sonra beyaz göbek; ardından ekran kaybolur.
/// Android'deki ortadaki mavi tohum nokta bilerek yok.
/// Ölçüler Android çiziminin 288'lik tuvaliyle aynıdır (1 birim = 1 pt).
final class SplashView: UIView {
    private static let brand = UIColor(red: 0x2A / 255.0, green: 0x38 / 255.0, blue: 0xEC / 255.0, alpha: 1)
    private let canvas = CALayer()

    /// [dark] sistem görünümüdür; statik açılış ekranıyla aynı zemin kullanılsın.
    init(frame: CGRect, dark: Bool) {
        super.init(frame: frame)
        autoresizingMask = [.flexibleWidth, .flexibleHeight]
        // Zemin: açık #FBFAF6, koyu #121213 (LaunchSurface ile aynı).
        backgroundColor = dark
            ? UIColor(red: 0x12 / 255.0, green: 0x12 / 255.0, blue: 0x13 / 255.0, alpha: 1)
            : UIColor(red: 0xFB / 255.0, green: 0xFA / 255.0, blue: 0xF6 / 255.0, alpha: 1)
        canvas.bounds = CGRect(x: 0, y: 0, width: 288, height: 288)
        layer.addSublayer(canvas)
    }

    required init?(coder: NSCoder) { nil }

    override func layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        canvas.position = CGPoint(x: bounds.midX, y: bounds.midY)
        CATransaction.commit()
    }

    /// Animasyonu oynatır; bitince ekranı soldurup kaldırır.
    func play(in window: UIWindow) {
        frame = window.bounds
        window.addSubview(self)

        let center = CGPoint(x: 144, y: 144)
        let ring = CAShapeLayer()
        ring.frame = canvas.bounds
        // Tepeden başlayıp saat yönünde dönen halka (r 62, kalınlık 36, düz uç).
        ring.path = UIBezierPath(arcCenter: center, radius: 62, startAngle: -.pi / 2, endAngle: 1.5 * .pi, clockwise: true).cgPath
        ring.fillColor = nil
        ring.strokeColor = Self.brand.cgColor
        ring.lineWidth = 36
        ring.lineCap = .butt
        let core = circle(radius: 46, color: .white)
        [ring, core].forEach(canvas.addSublayer)

        let now = CACurrentMediaTime()
        if UIAccessibility.isReduceMotionEnabled {
            // Hareket azaltılmışsa logo hazır görünür, yalnızca solarak kaybolur.
            dismiss(after: 0.5)
            return
        }
        core.transform = CATransform3DMakeScale(0, 0, 1)
        ring.strokeEnd = 0
        core.add(overshootScale(begin: now + 0.56, duration: 0.30), forKey: "core")

        let draw = CABasicAnimation(keyPath: "strokeEnd")
        draw.fromValue = 0
        draw.toValue = 1
        draw.beginTime = now + 0.16
        draw.duration = 0.52
        // Android fast_out_slow_in.
        draw.timingFunction = CAMediaTimingFunction(controlPoints: 0.4, 0, 0.2, 1)
        draw.fillMode = .both
        draw.isRemovedOnCompletion = false
        ring.add(draw, forKey: "ring")

        // Animasyon 860 ms'de biter; ardından ekran 200 ms'de solar (Android'deki gibi).
        dismiss(after: 0.86)
    }

    private func dismiss(after delay: TimeInterval) {
        UIView.animate(withDuration: 0.2, delay: delay, options: [.curveEaseOut]) {
            self.alpha = 0
        } completion: { _ in
            self.removeFromSuperview()
        }
    }

    private func circle(radius: CGFloat, color: UIColor) -> CAShapeLayer {
        let shape = CAShapeLayer()
        shape.frame = canvas.bounds
        shape.path = UIBezierPath(arcCenter: CGPoint(x: 144, y: 144), radius: radius, startAngle: 0, endAngle: 2 * .pi, clockwise: true).cgPath
        shape.fillColor = color.cgColor
        return shape
    }

    /// 0 → 1 ölçek, Android'in overshoot eğrisiyle (gerilim 2), örneklenmiş ara karelerle.
    private func overshootScale(begin: CFTimeInterval, duration: CFTimeInterval) -> CAKeyframeAnimation {
        let tension = 2.0
        let steps = 30
        let values: [NSValue] = (0...steps).map { i in
            let t = Double(i) / Double(steps) - 1
            let s = t * t * ((tension + 1) * t + tension) + 1
            return NSValue(caTransform3D: CATransform3DMakeScale(s, s, 1))
        }
        let anim = CAKeyframeAnimation(keyPath: "transform")
        anim.values = values
        anim.calculationMode = .linear
        anim.beginTime = begin
        anim.duration = duration
        anim.fillMode = .both
        anim.isRemovedOnCompletion = false
        return anim
    }
}
