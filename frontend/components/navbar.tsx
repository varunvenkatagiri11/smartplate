"use client"

import Link from "next/link"
import { usePathname, useRouter } from "next/navigation"
import { useEffect, useState } from "react"
import { TrendingUp, Sparkles, UtensilsCrossed, LogIn, LogOut, User, Heart } from "lucide-react"
import { cn } from "@/lib/utils"
import { getUser, logout, isLoggedIn } from "@/lib/auth"

export function Navbar() {
  const pathname = usePathname()
  const router = useRouter()

  // Read auth state from localStorage
  // useEffect ensures this only runs client-side (localStorage not available on server)
  const [user, setUser] = useState<{ email: string; userId: number } | null>(null)

  useEffect(() => {
    setUser(getUser())
  }, [pathname]) // re-check on every page change

  const handleLogout = () => {
    logout()
    setUser(null)
    router.push("/")
    router.refresh()
  }

  const links = [
    { href: "/", label: "Menu", icon: UtensilsCrossed },
    { href: "/trending", label: "Trending", icon: TrendingUp },
    { href: "/foryou", label: "For You", icon: Sparkles },
    ...(user ? [{ href: "/favorites", label: "Favorites", icon: Heart }] : []),
  ]

  return (
    <nav className="sticky top-0 z-50 w-full bg-white border-b border-gray-200 shadow-sm">
      <div className="max-w-6xl mx-auto px-6 h-14 flex items-center justify-between">
        {/* Logo */}
        <Link href="/" className="flex items-center gap-2 font-bold text-gray-900 text-lg">
          <UtensilsCrossed className="w-5 h-5 text-blue-600" />
          SmartPlate
        </Link>

        {/* Nav links */}
        <div className="flex items-center gap-1">
          {links.map(({ href, label, icon: Icon }) => (
            <Link
              key={href}
              href={href}
              className={cn(
                "flex items-center gap-1.5 px-4 py-2 rounded-full text-sm font-medium transition-colors",
                pathname === href
                  ? "bg-blue-600 text-white"
                  : "text-gray-600 hover:text-gray-900 hover:bg-gray-100"
              )}
            >
              <Icon className="w-4 h-4" />
              {label}
            </Link>
          ))}
        </div>

        {/* Auth section */}
        <div className="flex items-center gap-2">
          {user ? (
            // Logged in — show email and logout button
            <>
              <Link
                href="/favorites"
                className="flex items-center gap-1.5 text-sm text-gray-600 hover:text-gray-900 transition-colors"
              >
                <User className="w-4 h-4" />
                <span className="hidden sm:block">{user.email}</span>
              </Link>
              <button
                onClick={handleLogout}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-full text-sm text-gray-600 hover:bg-gray-100 transition-colors"
              >
                <LogOut className="w-4 h-4" />
                <span className="hidden sm:block">Logout</span>
              </button>
            </>
          ) : (
            // Not logged in — show login button
            <Link
              href="/login"
              className="flex items-center gap-1.5 px-4 py-2 rounded-full text-sm font-medium bg-blue-600 text-white hover:bg-blue-700 transition-colors"
            >
              <LogIn className="w-4 h-4" />
              Sign In
            </Link>
          )}
        </div>
      </div>
    </nav>
  )
}
