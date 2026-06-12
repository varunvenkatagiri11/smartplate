/**
 * lib/api.ts — updated to send JWT token on every request
 *
 * The axios interceptor runs before every request and injects
 * the Authorization header if a token exists in localStorage.
 */

import axios from "axios"
import { getToken } from "./auth"

const client = axios.create({
  baseURL: "/api",
  headers: { "Content-Type": "application/json" },
})

/**
 * Request interceptor — runs before every API call.
 * Reads the JWT from localStorage and adds it to the Authorization header.
 * If no token exists, the request goes through unauthenticated.
 */
client.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// ── Types ──────────────────────────────────────────────────────────────────

export interface Hall {
  hall_id: number
  name: string
  meal_period: string
}

export interface MenuItem {
  id: number
  name: string
  description?: string
  station?: string
  meal_period?: string
  calories?: number
  g_protein?: number
  g_carbs?: number
  g_fat?: number
  g_fiber?: number
  mg_sodium?: number
  mg_cholesterol?: number
  mg_calcium?: number
  is_vegan: boolean
  is_meatless: boolean
  is_halal: boolean
  is_gluten_friendly: boolean
  is_heart_healthy: boolean
  contains_milk: boolean
  contains_eggs: boolean
  contains_wheat: boolean
  contains_peanuts: boolean
  contains_tree_nuts: boolean
  contains_soy: boolean
  contains_fish: boolean
  contains_shellfish: boolean
  contains_sesame: boolean
  serving_size_amount?: string
  serving_size_unit?: string
  ingredients?: string
}

export interface TrendingItem extends MenuItem {
  trendingScore?: number
}

// ── Hall ID mapping ─────────────────────────────────────────────────────────

export const HALL_SLUG_TO_ID: Record<string, number> = {
  bolton: 44401,
  oglethorpe: 44402,
  snelling: 44403,
  niche: 44404,
  "village-summit": 44405,
}

// Maps school_id → database dining_halls.id (for events)
export const SCHOOL_ID_TO_DB_ID: Record<number, number> = {
  44401: 1,
  44402: 2,
  44403: 3,
  44404: 4,
  44405: 5,
}

// ── Auth API calls ──────────────────────────────────────────────────────────

export async function register(
  email: string,
  password: string,
  prefs: Record<string, boolean> = {}
): Promise<{ token: string; userId: number; email: string }> {
  const { data } = await client.post("/v1/auth/register", { email, password, ...prefs })
  return data
}

export async function login(
  email: string,
  password: string
): Promise<{ token: string; userId: number; email: string }> {
  const { data } = await client.post("/v1/auth/login", { email, password })
  return data
}

export async function getMe(): Promise<Record<string, unknown>> {
  const { data } = await client.get("/v1/auth/me")
  return data
}

// ── Menu API calls ──────────────────────────────────────────────────────────

export async function getHalls(): Promise<Hall[]> {
  const { data } = await client.get("/v1/menu/halls")
  return data
}

export async function getMenu(
  hallId: number,
  mealPeriod: "breakfast" | "lunch_dinner",
  date?: string,
  filters?: {
    isVegan?: boolean
    isMeatless?: boolean
    isHalal?: boolean
    isGlutenFriendly?: boolean
  }
): Promise<MenuItem[]> {
  const params: Record<string, string | number | boolean> = { hallId, mealPeriod }
  if (date) params.date = date
  if (filters?.isVegan) params.isVegan = true
  if (filters?.isMeatless) params.isMeatless = true
  if (filters?.isHalal) params.isHalal = true
  if (filters?.isGlutenFriendly) params.isGlutenFriendly = true
  const { data } = await client.get("/v1/menu", { params })
  return data
}

// ── Recommendation API calls ────────────────────────────────────────────────

export async function getTrending(
  hallId: number,
  daypart: number,
  limit = 10
): Promise<TrendingItem[]> {
  const { data } = await client.get("/v1/recommendations/trending", {
    params: { hallId, daypart, limit },
  })
  return data
}

export async function getForYou(
  userId: number,
  hallId: number,
  daypart: number,
  limit = 10
): Promise<MenuItem[]> {
  const { data } = await client.get("/v1/recommendations/foryou", {
    params: { userId, hallId, daypart, limit },
  })
  return data
}

export async function getSimilar(
  itemId: number,
  hallId: number,
  limit = 6
): Promise<MenuItem[]> {
  const { data } = await client.get("/v1/recommendations/similar", {
    params: { itemId, hallId, limit },
  })
  return data
}

export async function getFavoriteItems(): Promise<MenuItem[]> {
  const { data } = await client.get("/v1/users/me/favorites/items")
  return data as MenuItem[]
}

export async function getFavorites(): Promise<number[]> {
  const { data } = await client.get("/v1/users/me/favorites")
  return data.itemIds as number[]
}

export async function getRatings(): Promise<Record<number, number>> {
  const { data } = await client.get("/v1/users/me/ratings")
  return data.ratings as Record<number, number>
}

export async function postEvent(
  userId: number,
  itemId: number,
  diningHallId: number,
  eventType: "view" | "click" | "rate" | "favorite" | "unfavorite",
  ratingValue?: number
): Promise<void> {
  await client.post("/v1/events", {
    userId,
    itemId,
    diningHallId,
    eventType,
    ratingValue: ratingValue ?? null,
  })
}

// ── Helpers ─────────────────────────────────────────────────────────────────

export function currentDaypart(): number {
  const hour = new Date().getHours()
  if (hour < 11) return 0
  if (hour < 16) return 1
  return 2
}

export function daypartLabel(daypart: number): string {
  return ["Breakfast", "Lunch", "Dinner"][daypart] ?? "Lunch"
}
