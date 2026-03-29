"use client"

import React, {useEffect, useState} from "react";
import {useRouter} from "next/navigation";
import { getErrorMessage, resendVerificationCode, verifyEmail } from "@/lib/api";

export default function Verify() { // 🔑 keep the name Pascal-cased

    const [message, setMessage] = useState<{ text: string; ok: boolean } | null>(null); // message is initialized to null
    const [cooldown, setCooldown] = useState(0);
    const [verificationData, setVerificationData] = useState({ verificationCode: "" });
    const [email, setEmail] = useState("");
    const [error, setError] = useState("");
    const router = useRouter();

    useEffect(() => {
        const queryParams = new URLSearchParams(window.location.search);
        const emailParams = queryParams.get("email");
        if (emailParams) {
            setEmail(decodeURIComponent(emailParams));
        } else {
            setError("Email missing. Please restart registration.");
        }
    }, []);

    const submitCode = async (e: React.FormEvent) => {
        e.preventDefault();
        try {
            setError("");
            await verifyEmail(email, verificationData.verificationCode);
            router.push("/login");
        } catch (err) {
            setError(getErrorMessage(err, "Verification failed! The code you entered is incorrect"));
        }
    };

    const handleResend = async () => {
        if (!email || cooldown > 0) return;

        try {
            setMessage(null);
            await resendVerificationCode(email);
            setMessage({ text: "Verification code resent!", ok: true });
            setCooldown(30);
        } catch (err) {
            setMessage({ text: getErrorMessage(err, "Could not resend code."), ok: false });
        }
    }

    useEffect(() => {
        if (cooldown === 0) return; // don't start timer
        const id = setInterval(() => setCooldown((t) => t-1), 1000);
        return () => clearInterval(id); //cleanup function.
    }, [cooldown]);

    /* ------------------------------- render -------------------------------- */
    return (
        <div className="min-h-screen flex flex-col items-center justify-center bg-black px-4 text-white">
            {/* icon + headings */}
            <div className="flex flex-col items-center">
                <div className="w-16 h-16 mb-6 flex items-center justify-center rounded-full bg-[#1d1d1d]">
                    {/* simple mail outline */}
                    <svg
                        xmlns="http://www.w3.org/2000/svg"
                        className="w-9 h-9"
                        fill="none"
                        viewBox="0 0 24 24"
                        strokeWidth={2}
                        stroke="#f59e0b" /* amber-500 */
                    >
                        <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            d="M3 8l9 6 9-6M4 6h16a2 2 0 012 2v8a2 2 0 01-2 2H4a2 2 0 01-2-2V8a2 2 0 012-2z"
                        />
                    </svg>
                </div>

                <h1 className="text-3xl font-bold mb-2">Check your email</h1>
                <p className="text-gray-400 mb-8 text-center">
                    We have sent the verification code to your email address.
                </p>
            </div>

            {/* form */}
            <form
                onSubmit={submitCode}
                className="w-full max-w-md space-y-6"
            >
                <div>
                    <label
                        htmlFor="code"
                        className="block mb-2 text-sm font-medium text-gray-300"
                    >
                        Verification Code
                    </label>

                    <input
                        id="code"
                        type="text"
                        required
                        placeholder="Verification Code"
                        value={verificationData.verificationCode}
                        onChange={(e) =>
                            setVerificationData({ ...verificationData, verificationCode: e.target.value })
                        }
                        className="w-full h-12 rounded-lg bg-[#1d1d1d] border border-gray-700 text-lg text-center tracking-widest
                       placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-offset-white"
                    />
                </div>

                <button
                    type="submit"
                    // onClick={() => {router.push("/login")}}
                    className="w-full h-12 rounded-lg bg-[#8b5a2b] hover:bg-[#a56c34] font-semibold transition-colors"
                >
                    Verify Email
                </button>

                <div className="text-center text-sm">
                    <span className="text-gray-400">Didn&apos;t receive the code?</span>
                    <button
                        type="button"
                        onClick={handleResend}
                        disabled={cooldown > 0}
                        className={`ml-1 underline transition-colors ${cooldown > 0 ? "text-gray-600 cursor-not-allowed" : "text-amber-500 hover:text-amber-400"}`}
                    >
                        {cooldown > 0 ? `Resend in ${cooldown}s` : "Resend code"}
                    </button>
                </div>
            </form>

            {/* success / error banner */}
            {message && (
                <p
                    className={`mt-4 text-sm ${
                        message.ok ? "text-emerald-400" : "text-red-500"
                    }`}
                >
                    {message.text}
                </p>
            )}
            {error && <p className="mt-4 text-red-500 text-sm">{error}</p>}

            {/* back link */}
            <button
                onClick={() => router.push("/register")}
                className="mt-10 flex items-center text-gray-400 hover:text-gray-200"
            >
                {/* arrow-left */}
                <svg
                    xmlns="http://www.w3.org/2000/svg"
                    className="w-4 h-4"
                    fill="none"
                    viewBox="0 0 24 24"
                    strokeWidth={2}
                    stroke="currentColor"
                >
                    <path strokeLinecap="round" strokeLinejoin="round" d="M15 19l-7-7 7-7" />
                </svg>
                <span className="ml-2">Sign Up</span>
            </button>
        </div>
    );
}
