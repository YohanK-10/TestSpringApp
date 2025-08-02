"use client"

import React, {useState} from "react";

export default function verifyPage() {
    const [verificationData, setVerificationData] = useState({verificationCode: ''});
    return (
        <div className="flex min-h-screen bg-black overflow-hidden">
            <div className="w-full flex items-center justify-center p-4">
                <div className="w-full max-w-md bg-gray-700 rounded-xl shadow-lg p-8 border border-gray-800">
                    <div className="text-center mb-8">
                        <h1 className="text-2xl font-bold text-white mb-2">Verify Your Email</h1>
                        <p className="text-gray-400">
                            Please enter the verification code sent to your email.
                        </p>
                    </div>

                    <form className="space-y-6 focus-visible:outline-none focus-visible:ring-2">
                        {/* Verification Code Input */}
                        <div className="space-y-2">
                            <label htmlFor="code" className="block text-shadow-md font-medium pl-2 text-gray-300">
                                Verification code
                            </label>
                            <div className="flex space-x-3 justify-center">
                                <input
                                    className="flex h-11 w-full rounded-lg border border-gray-500 bg-transparent pl-6 py-2 text-base transition-colors
                                                       focus-visible:outline-none focus-visible:ring-2 hover:border-gray-300 focus:border-white
                                                       "
                                    id="emailOrUsername"
                                    type="text"
                                    placeholder="Verification Code"
                                    autoCapitalize="none"
                                    autoComplete="email"
                                    autoCorrect="off"
                                    value={verificationData.verificationCode}
                                    onChange={(e)=> {
                                        setVerificationData({...verificationData, verificationCode: e.target.value}) // copy content from the formData object and change the value of loginInfo. ... is the spread operator.
                                    }}
                                    required
                                />
                            </div>
                        </div>

                        {/* Submit Button */}
                        <button
                            type="submit"
                            className="w-full h-11 pt-2 pb-3 bg-gray-300 hover:bg-gray-100 rounded-lg focus-visible:outline-none focus-visible:ring-2
                            text-black border-b-gray-500 font-medium transition duration-200"
                        >
                            Verify Account
                        </button>

                        {/* Resend Code */}
                        <div className="text-center text-sm pt-4">
                            <p className="text-gray-400">
                                Didn't receive the code?{' '}
                                <button
                                    type="button"
                                    className="text-blue-400 hover:text-blue-300 font-medium underline"
                                >
                                    Resend
                                </button>
                            </p>
                        </div>
                    </form>
                </div>
            </div>
        </div>
    )
}