"use client"

import { useState } from "react"
import { useRouter } from "next/navigation"
import Link from "next/link"
import { UtensilsCrossed } from "lucide-react"
import { Card } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Button } from "@/components/ui/button"
import { register } from "@/lib/api"
import { saveAuth } from "@/lib/auth"
import { cn } from "@/lib/utils"

// Dietary preference options shown during onboarding
const DIET_OPTIONS = [
  { key: "prefVegan",      label: "Vegan",         icon: "🌱" },
  { key: "prefMeatless",   label: "Vegetarian",    icon: "🥦" },
  { key: "prefHalal",      label: "Halal",         icon: "☪️" },
  { key: "prefGlutenFree", label: "Gluten Free",   icon: "🌾" },
]

const ALLERGEN_OPTIONS = [
  { key: "avoidMilk",      label: "Milk",          icon: "🥛" },
  { key: "avoidEggs",      label: "Eggs",          icon: "🥚" },
  { key: "avoidWheat",     label: "Wheat",         icon: "🌾" },
  { key: "avoidPeanuts",   label: "Peanuts",       icon: "🥜" },
  { key: "avoidTreeNuts",  label: "Tree Nuts",     icon: "🌰" },
  { key: "avoidSoy",       label: "Soy",           icon: "🫘" },
  { key: "avoidFish",      label: "Fish",          icon: "🐟" },
  { key: "avoidShellfish", label: "Shellfish",     icon: "🦐" },
  { key: "avoidSesame",    label: "Sesame",        icon: "🌰" },
]

export default function RegisterPage() {
  const router = useRouter()
  const [step, setStep] = useState<1 | 2>(1)
  const [email, setEmail] = useState("")
  const [password, setPassword] = useState("")
  const [prefs, setPrefs] = useState<Record<string, boolean>>({})
  const [error, setError] = useState("")
  const [loading, setLoading] = useState(false)

  const togglePref = (key: string) => {
    setPrefs(p => ({ ...p, [key]: !p[key] }))
  }

  const handleStep1 = (e: React.FormEvent) => {
    e.preventDefault()
    if (password.length < 6) {
      setError("Password must be at least 6 characters")
      return
    }
    setError("")
    setStep(2)
  }

  const handleSubmit = async () => {
    setError("")
    setLoading(true)
    try {
      const result = await register(email, password, prefs)
      saveAuth({ token: result.token, userId: result.userId, email: result.email })
      router.push("/")
      router.refresh()
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { error?: string } } })
        ?.response?.data?.error ?? "Registration failed"
      setError(msg)
      setStep(1)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-gray-50 flex items-center justify-center p-6">
      <div className="w-full max-w-md">
        {/* Logo */}
        <div className="text-center mb-8">
          <div className="flex items-center justify-center gap-2 mb-2">
            <UtensilsCrossed className="w-8 h-8 text-blue-600" />
            <span className="text-2xl font-bold text-gray-900">SmartPlate</span>
          </div>
          <p className="text-gray-500">
            {step === 1 ? "Create your account" : "Set your dietary preferences"}
          </p>
        </div>

        <Card className="p-6 bg-white">
          {step === 1 ? (
            // ── Step 1: email + password ──────────────────────────────
            <form onSubmit={handleStep1} className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  Email
                </label>
                <Input
                  type="email"
                  placeholder="you@uga.edu"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  required
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  Password
                </label>
                <Input
                  type="password"
                  placeholder="At least 6 characters"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  required
                />
              </div>

              {error && (
                <p className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded-lg">
                  {error}
                </p>
              )}

              <Button type="submit" className="w-full bg-blue-600 hover:bg-blue-700 text-white">
                Continue
              </Button>

              <p className="text-center text-sm text-gray-500">
                Already have an account?{" "}
                <Link href="/login" className="text-blue-600 hover:underline font-medium">
                  Sign in
                </Link>
              </p>
            </form>
          ) : (
            // ── Step 2: dietary preferences ───────────────────────────
            <div className="space-y-5">
              <div>
                <p className="text-sm font-semibold text-gray-700 mb-2">Diet</p>
                <div className="flex flex-wrap gap-2">
                  {DIET_OPTIONS.map(({ key, label, icon }) => (
                    <button
                      key={key}
                      onClick={() => togglePref(key)}
                      className={cn(
                        "flex items-center gap-1.5 px-3 py-1.5 rounded-full text-sm font-medium border transition-colors",
                        prefs[key]
                          ? "bg-green-600 text-white border-green-600"
                          : "bg-white text-gray-700 border-gray-300 hover:bg-gray-50"
                      )}
                    >
                      <span>{icon}</span>
                      {label}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <p className="text-sm font-semibold text-gray-700 mb-2">Allergens to avoid</p>
                <div className="flex flex-wrap gap-2">
                  {ALLERGEN_OPTIONS.map(({ key, label, icon }) => (
                    <button
                      key={key}
                      onClick={() => togglePref(key)}
                      className={cn(
                        "flex items-center gap-1.5 px-3 py-1.5 rounded-full text-sm font-medium border transition-colors",
                        prefs[key]
                          ? "bg-red-500 text-white border-red-500"
                          : "bg-white text-gray-700 border-gray-300 hover:bg-gray-50"
                      )}
                    >
                      <span>{icon}</span>
                      {label}
                    </button>
                  ))}
                </div>
              </div>

              {error && (
                <p className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded-lg">
                  {error}
                </p>
              )}

              <div className="flex gap-3">
                <Button
                  variant="outline"
                  onClick={() => setStep(1)}
                  className="flex-1"
                  disabled={loading}
                >
                  Back
                </Button>
                <Button
                  onClick={handleSubmit}
                  className="flex-1 bg-blue-600 hover:bg-blue-700 text-white"
                  disabled={loading}
                >
                  {loading ? "Creating account..." : "Create Account"}
                </Button>
              </div>

              <p className="text-center text-xs text-gray-400">
                You can update these preferences later
              </p>
            </div>
          )}
        </Card>
      </div>
    </div>
  )
}
